package net.natural.motionblur.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.natural.motionblur.NaturalMotionBlurMod;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
import net.natural.motionblur.util.GpuBufferUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class RecordingShaderManager {

    private static final int SCALAR_UBO_SIZE = 16;
    private static final int CURSOR_UBO_SIZE = 32;
    private static final int MAX_HISTORY = 8;
    private static final int WINDOW_CHANGE_HOLD_FRAMES = 6;

    private static GraphicsResourceAllocator savedAllocator = null;

    private static RenderTarget cleanFrameTarget = null;
    private static final RenderTarget[] recHistoryTargets = new RenderTarget[MAX_HISTORY];
    private static int lastW = 0;
    private static int lastH = 0;

    private static PostChain recCombineChain = null;
    private static GpuBuffer recCombineUBO = null;

    private static PostChain recCursorChain = null;
    private static GpuBuffer recCursorUBO   = null;

    private static final ResourceLocation CURSOR_TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "textures/gui/obs_cursor.png");
    private static PostPass.Input recCursorTextureInput = null;

    private static boolean recHasFirstFrame        = false;
    private static int     recHistoryWriteIndex    = 0;
    private static int     recHistoryFilled        = 0;
    private static int     recLockedN              = 1;
    private static int     recPendingN             = 1;
    private static int     recPendingFrames        = 0;
    private static float   recSmoothedFPS          = 0;
    private static float   recPrevRawCursorX       = 0;
    private static float   recPrevRawCursorY       = 0;
    private static boolean recPrevRawCursorVisible = false;


    // Cached GLFW window handle (resolved once via reflection)
    private static long    cachedGlfwHandle        = 0;
    private static boolean glfwHandleResolved      = false;

    public static void captureAllocator(GraphicsResourceAllocator allocator) {
        savedAllocator = allocator;
    }

    public static void captureFinalFrameAndPresent() {
        ConfigEntries cfg = ConfigManager.getConfig();
        try {
            if (!cfg.recordingOverlayEnabled || savedAllocator == null) return;

            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width;
            int h = main.height;
            ensureTargets(w, h);

            float realFps = ShaderManager.getCurrentFPS();
            int targetHz = Math.max(1, cfg.recordingOverlayTargetFPS);

            copyTexture(main, cleanFrameTarget);
            applyIsolatedFrameBlending(main, realFps, targetHz, w, h);

            if (recHasFirstFrame) {
                int glTexId = GpuTextureHelper.getGlId(main.getColorTexture());
                if (glTexId != 0) SpoutBridge.sendTexture(glTexId, w, h);
            }

            copyTexture(cleanFrameTarget, main);
        } finally {
            savedAllocator = null;
        }
    }

    private static void applyIsolatedFrameBlending(RenderTarget main, float fps,
                                                   int refreshRate, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        updateLockedWindowSize(fps, refreshRate);

        applyCursorOverlay(main, mc, w, h);
        pushHistoryFrame(main);

        int sampleCount = Math.min(recHistoryFilled, recLockedN);
        if (sampleCount <= 0) {
            recHasFirstFrame = true;
            return;
        }

        int oldestIndex = oldestHistoryIndex(sampleCount);

        recCombineChain = loadChain(mc, recCombineChain, "frame_blending");
        if (recCombineChain == null) { recHasFirstFrame = true; return; }
        PostPass combinePass = firstPass(recCombineChain);
        if (combinePass == null) { recHasFirstFrame = true; return; }
        Map<String, GpuBuffer> combineUniforms = ((PostPassAccessor) combinePass).getCustomUniforms();
        if (!combineUniforms.containsKey("FrameBlendParamsUniforms")) {
            recHasFirstFrame = true; return;
        }
        if (recCombineUBO == null) recCombineUBO = GpuBufferUtil.createUBO("RecFrameBlendParamsUniforms", SCALAR_UBO_SIZE);
        GpuBuffer savedCombineUBO = combineUniforms.put("FrameBlendParamsUniforms", recCombineUBO);
        writeBlendParamsUBO(recCombineUBO, 1.0f / sampleCount, sampleCount);

        RenderTarget fallback = recHistoryTargets[oldestIndex];
        PostPass.Input[] savedSamplers = new PostPass.Input[MAX_HISTORY];
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget target = (i < sampleCount)
                    ? recHistoryTargets[(oldestIndex + i) % MAX_HISTORY]
                    : fallback;
            savedSamplers[i] = swapSampler(combinePass, "Sample" + i, new PersistentTextureInput("Sample" + i, target));
        }

        recCombineChain.process(main, savedAllocator);

        if (savedCombineUBO != null) combineUniforms.put("FrameBlendParamsUniforms", savedCombineUBO);
        for (int i = 0; i < MAX_HISTORY; i++) {
            if (savedSamplers[i] != null) swapSampler(combinePass, "Sample" + i, savedSamplers[i]);
        }

        recHasFirstFrame = true;
    }

    private static void updateLockedWindowSize(float fps, int refreshRate) {
        if (fps > 0.0f) {
            recSmoothedFPS = (recSmoothedFPS <= 0.0f) ? fps : recSmoothedFPS * 0.85f + fps * 0.15f;
        }

        int desired = 1;
        if (recSmoothedFPS > 0.0f && refreshRate > 0) {
            desired = Math.clamp(Math.round(recSmoothedFPS / refreshRate), 1, MAX_HISTORY);
        }

        if (desired == recLockedN) {
            recPendingN = desired;
            recPendingFrames = 0;
            return;
        }

        if (desired != recPendingN) {
            recPendingN = desired;
            recPendingFrames = 1;
            return;
        }

        recPendingFrames++;
        if (recPendingFrames >= WINDOW_CHANGE_HOLD_FRAMES) {
            recLockedN = recPendingN;
            recPendingFrames = 0;
        }
    }

    private static void pushHistoryFrame(RenderTarget src) {
        if (recHistoryTargets[recHistoryWriteIndex] == null) return;
        copyTexture(src, recHistoryTargets[recHistoryWriteIndex]);
        recHistoryWriteIndex = (recHistoryWriteIndex + 1) % MAX_HISTORY;
        if (recHistoryFilled < MAX_HISTORY) recHistoryFilled++;
    }

    private static int oldestHistoryIndex(int sampleCount) {
        int idx = recHistoryWriteIndex - sampleCount;
        if (idx < 0) idx += MAX_HISTORY;
        return idx;
    }

    private static void applyCursorOverlay(RenderTarget target, Minecraft mc, int w, int h) {
        CursorState cursor = getCursorState(mc, w, h);
        if (!cursor.visible) {
            recPrevRawCursorVisible = false;
            return;
        }

        float prevDrawX = cursor.x;
        float prevDrawY = cursor.y;
        if (recPrevRawCursorVisible) {
            prevDrawX = recPrevRawCursorX;
            prevDrawY = recPrevRawCursorY;
        }

        recCursorChain = loadChain(mc, recCursorChain, "cursor_overlay");
        if (recCursorChain == null) {
            return;
        }
        PostPass cursorPass = firstPass(recCursorChain);
        if (cursorPass == null) {
            return;
        }

        Map<String, GpuBuffer> cursorUniforms = ((PostPassAccessor) cursorPass).getCustomUniforms();
        if (!cursorUniforms.containsKey("CursorOverlayUniforms")) {
            return;
        }

        if (recCursorUBO == null) recCursorUBO = GpuBufferUtil.createUBO("CursorOverlayUniforms", CURSOR_UBO_SIZE);
        GpuBuffer savedCursorUBO = cursorUniforms.put("CursorOverlayUniforms", recCursorUBO);
        writeCursorUBO(recCursorUBO, cursor.x, cursor.y, cursor.scale, prevDrawX, prevDrawY);

        if (recCursorTextureInput == null) {
            recCursorTextureInput = new ResourceTextureInput("Cursor", CURSOR_TEXTURE_ID);
        }

        GpuTextureView testView = getTextureView(mc, CURSOR_TEXTURE_ID);
        if (testView == null) {
            if (savedCursorUBO != null) cursorUniforms.put("CursorOverlayUniforms", savedCursorUBO);
            return;
        }

        PostPass.Input savedCursorSampler = swapSampler(cursorPass, "Cursor", recCursorTextureInput);

        recCursorChain.process(target, savedAllocator);

        recPrevRawCursorX = cursor.x;
        recPrevRawCursorY = cursor.y;
        recPrevRawCursorVisible = true;

        if (savedCursorUBO != null) cursorUniforms.put("CursorOverlayUniforms", savedCursorUBO);
        if (savedCursorSampler != null) swapSampler(cursorPass, "Cursor", savedCursorSampler);
    }

    private static void writeCursorUBO(GpuBuffer ubo,
                                       float cursorX, float cursorY, float cursorScale,
                                       float prevCursorX, float prevCursorY) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putFloat(cursorX);
            b.putFloat(cursorY);
            b.putFloat(cursorScale);
            b.putFloat(1.0f);
            b.putFloat(prevCursorX);
            b.putFloat(prevCursorY);
            b.putFloat(1.0f);
            b.putFloat(0.0f);
        }
    }

    private static CursorState getCursorState(Minecraft mc, int renderWidth, int renderHeight) {
        try {
            long window = getGlfwWindowHandle(mc);
            if (window == 0) return CursorState.HIDDEN;

            // Check if cursor is grabbed (GLFW_CURSOR_DISABLED = mouse locked for gameplay)
            int cursorMode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
            if (cursorMode == GLFW.GLFW_CURSOR_DISABLED) return CursorState.HIDDEN;

            // Read cursor position directly from GLFW
            double[] xArr = new double[1];
            double[] yArr = new double[1];
            GLFW.glfwGetCursorPos(window, xArr, yArr);

            double winW = mc.getWindow().getWidth();
            double winH = mc.getWindow().getHeight();
            if (winW <= 0 || winH <= 0) return CursorState.HIDDEN;

            float x = (float)(xArr[0] * renderWidth  / winW);
            float y = (float)(yArr[0] * renderHeight / winH);
            if (x < -32 || y < -32 || x > renderWidth + 32 || y > renderHeight + 32)
                return CursorState.HIDDEN;

            return new CursorState(x, y, 1.0f, true);
        } catch (Throwable e) {
            return CursorState.HIDDEN;
        }
    }

    // Resolves the GLFW window handle from Minecraft's Window object via reflection. The handle is a {@code long} field - scan for it once and cache the result.
    private static long getGlfwWindowHandle(Minecraft mc) {
        if (glfwHandleResolved) return cachedGlfwHandle;
        try {
            Object windowObj = mc.getWindow();
            Class<?> type = windowObj.getClass();
            while (type != null) {
                for (Field f : type.getDeclaredFields()) {
                    if (f.getType() == long.class) {
                        f.setAccessible(true);
                        long val = f.getLong(windowObj);
                        // GLFW handles are always > 0; skip zero/negative fields
                        if (val > 0) {
                            // Verify it's actually a valid GLFW window by querying it
                            try {
                                GLFW.glfwGetInputMode(val, GLFW.GLFW_CURSOR);
                                cachedGlfwHandle = val;
                                glfwHandleResolved = true;
                                return cachedGlfwHandle;
                            } catch (Throwable ignored) {
                                // Not the right long field, keep scanning
                            }
                        }
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        glfwHandleResolved = true; // Don't retry every frame
        return 0;
    }

    private record CursorState(float x, float y, float scale, boolean visible) {
        private static final CursorState HIDDEN = new CursorState(0.0f, 0.0f, 1.0f, false);
    }

    public static void invalidateFrameBlending() {
        for (int i = 0; i < recHistoryTargets.length; i++) {
            if (recHistoryTargets[i] != null) {
                recHistoryTargets[i].destroyBuffers();
                recHistoryTargets[i] = null;
            }
        }
        recCombineChain = null;
        recCursorChain = null;
        recCombineUBO = null;
        recCursorUBO = null;
        recCursorTextureInput = null;
        recHasFirstFrame = false;
        recHistoryWriteIndex = 0;
        recHistoryFilled = 0;
        recLockedN = 1;
        recPendingN = 1;
        recPendingFrames = 0;
        recSmoothedFPS = 0.0f;
        recPrevRawCursorVisible = false;
    }

    public static void destroy() {
        if (cleanFrameTarget != null) { cleanFrameTarget.destroyBuffers(); cleanFrameTarget = null; }
        savedAllocator = null;
        invalidateFrameBlending();
        lastW = 0;
        lastH = 0;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;
        SpoutBridge.shutdown();
    }

    private static void ensureTargets(int w, int h) {
        if (cleanFrameTarget != null && lastW == w && lastH == h && recHistoryTargets[0] != null) return;
        if (cleanFrameTarget != null) cleanFrameTarget.destroyBuffers();
        cleanFrameTarget = new MainTarget(w, h);
        invalidateFrameBlending();
        for (int i = 0; i < recHistoryTargets.length; i++) {
            recHistoryTargets[i] = new MainTarget(w, h);
        }
        lastW = w;
        lastH = h;
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        assert src.getColorTexture() != null;
        assert dst.getColorTexture() != null;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, dst.width, dst.height);
    }

    private static PostPass.Input swapSampler(PostPass pass, String samplerName, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (samplerName.equals(inputs.get(i).samplerName())) {
                PostPass.Input old = inputs.get(i);
                inputs.set(i, replacement);
                return old;
            }
        }
        return null;
    }

    private static PostChain loadChain(Minecraft mc, PostChain cached, String shaderPath) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) mc.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain result = cache.getOrLoadPostChain(
                    ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderPath),
                    LevelTargetBundle.MAIN_TARGETS);
            if (result != cached) {
                switch (shaderPath) {
                    case "frame_blending" -> recCombineUBO = null;
                    case "cursor_overlay" -> recCursorUBO = null;
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, float invSampleCount, int sampleCount) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putFloat(invSampleCount);
            b.putInt(sampleCount);
            b.putInt(0);
            b.putInt(0);
        }
    }

    private static class PersistentTextureInput implements PostPass.Input {
        private final String samplerName;
        private final RenderTarget target;

        PersistentTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target = target;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<ResourceLocation, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public com.mojang.blaze3d.textures.@NonNull GpuTextureView texture(
                @NonNull Map<ResourceLocation, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            assert target.getColorTextureView() != null;
            return target.getColorTextureView();
        }

        @Override public @NonNull String samplerName() { return samplerName; }
    }

    private static class ResourceTextureInput implements PostPass.Input {
        private final String samplerName;
        private final ResourceLocation textureId;

        ResourceTextureInput(String samplerName, ResourceLocation textureId) {
            this.samplerName = samplerName;
            this.textureId = textureId;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<ResourceLocation, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @Nullable GpuTextureView texture(@NonNull Map<ResourceLocation, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            Minecraft mc = Minecraft.getInstance();
            return getTextureView(mc, textureId);
        }

        @Override public @NonNull String samplerName() { return samplerName; }
    }

    private static GpuTextureView getTextureView(Minecraft mc, ResourceLocation textureId) {
        try {
            return mc.getTextureManager().getTexture(textureId).getTextureView();
        } catch (Throwable ignored) {
        }
        return null;
    }
}