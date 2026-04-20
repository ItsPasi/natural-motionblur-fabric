package net.natural.motionblur.recording;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
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
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RecordingShaderManager {

    private static final int MAX_HISTORY = 8;
    private static final int WINDOW_CHANGE_HOLD_FRAMES = 6;

    private static GraphicsResourceAllocator savedAllocator = null;

    private static RenderTarget cleanFrameTarget = null;
    private static final RenderTarget[] recHistoryTargets = new RenderTarget[MAX_HISTORY];
    private static int lastW = 0;
    private static int lastH = 0;

    // Frame blending
    private static PostChain recCombineChain = null;
    private static boolean recHasFirstFrame        = false;
    private static int     recHistoryWriteIndex    = 0;
    private static int     recHistoryFilled        = 0;
    private static int     recLockedN              = 1;
    private static int     recPendingN             = 1;
    private static int     recPendingFrames        = 0;
    private static float   recSmoothedFPS          = 0;

    // Cursor overlay
    private static PostChain recCursorChain = null;
    private static final ResourceLocation CURSOR_TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "textures/gui/obs_cursor.png");
    private static float   recPrevRawCursorX       = 0;
    private static float   recPrevRawCursorY       = 0;
    private static boolean recPrevRawCursorVisible = false;

    // GLFW window handle
    private static long    cachedGlfwHandle        = 0;
    private static boolean glfwHandleResolved      = false;

    // Texture manager reflection
    private static Method  cachedGetTextureMethod   = null;
    private static Method  cachedGetGpuTextureMethod = null;
    private static boolean textureMethodsResolved   = false;
    private static GpuTexture cachedCursorGpuTexture = null;

    private static ConfigEntries.BlurAlgorithm lastSeenBlurAlgorithm = null;

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

            if (lastSeenBlurAlgorithm != cfg.blurAlgorithm) {
                invalidateFrameBlending();
                ensureTargets(w, h);
                lastSeenBlurAlgorithm = cfg.blurAlgorithm;
            }

            float realFps = ShaderManager.getCurrentFPS();
            int targetHz = Math.max(1, cfg.recordingOverlayTargetFPS);

            copyFramebuffer(main, cleanFrameTarget);
            applyCursorOverlay(main, mc, w, h);
            applyIsolatedFrameBlending(main, realFps, targetHz);

            if (recHasFirstFrame) {
                int glTexId = (main.getColorTexture() != null) ? GpuTextureHelper.getGlId(main.getColorTexture()) : 0;
                if (glTexId != 0) {
                    GL11.glFinish();
                    SpoutBridge.sendTexture(glTexId, w, h);
                }
            }

            copyFramebuffer(cleanFrameTarget, main);
        } finally {
            savedAllocator = null;
        }
    }

    // Cursor Overlay

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

        recCursorChain = loadChain(mc, "cursor_overlay");
        if (recCursorChain == null) return;

        PostPass cursorPass = firstPass(recCursorChain);
        if (cursorPass == null) return;

        GpuTexture cursorTex = getCursorGpuTexture(mc);
        if (cursorTex == null) return;

        setSampler(cursorPass, new GpuTextureInput("Cursor", cursorTex));

        float cx = cursor.x, cy = cursor.y, cs = cursor.scale;
        float pdx = prevDrawX, pdy = prevDrawY;

        recCursorChain.process(target, savedAllocator, (RenderPass rp) -> {
            trySetUniform(rp, "cursorX",       new float[]{cx});
            trySetUniform(rp, "cursorY",       new float[]{cy});
            trySetUniform(rp, "cursorScale",   new float[]{cs});
            trySetUniform(rp, "cursorVisible", new float[]{1.0f});
            trySetUniform(rp, "prevCursorX",   new float[]{pdx});
            trySetUniform(rp, "prevCursorY",   new float[]{pdy});
            trySetUniform(rp, "blurStrength",  new float[]{1.0f});
        });

        recPrevRawCursorX = cursor.x;
        recPrevRawCursorY = cursor.y;
        recPrevRawCursorVisible = true;
    }

    private record CursorState(float x, float y, float scale, boolean visible) {
        private static final CursorState HIDDEN = new CursorState(0.0f, 0.0f, 1.0f, false);
    }

    private static CursorState getCursorState(Minecraft mc, int renderWidth, int renderHeight) {
        try {
            long window = getGlfwWindowHandle(mc);
            if (window == 0) return CursorState.HIDDEN;

            int cursorMode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
            if (cursorMode == GLFW.GLFW_CURSOR_DISABLED) return CursorState.HIDDEN;

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
                        if (val > 0) {
                            try {
                                GLFW.glfwGetInputMode(val, GLFW.GLFW_CURSOR);
                                cachedGlfwHandle = val;
                                glfwHandleResolved = true;
                                return cachedGlfwHandle;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {}
        glfwHandleResolved = true;
        return 0;
    }

    // Cursor Texture Resolution

    private static GpuTexture getCursorGpuTexture(Minecraft mc) {
        if (cachedCursorGpuTexture != null) return cachedCursorGpuTexture;

        try {
            Object textureManager = mc.getTextureManager();
            if (!textureMethodsResolved) {
                resolveTextureMethods(textureManager);
                textureMethodsResolved = true;
            }
            if (cachedGetTextureMethod == null) return null;

            Object abstractTexture;
            try {
                abstractTexture = cachedGetTextureMethod.invoke(textureManager, CURSOR_TEXTURE_ID);
            } catch (Exception e) {
                if (!registerTexture(textureManager)) return null;
                abstractTexture = cachedGetTextureMethod.invoke(textureManager, CURSOR_TEXTURE_ID);
            }
            if (abstractTexture == null) return null;

            ensureTextureLoaded(abstractTexture, mc);

            if (cachedGetGpuTextureMethod == null) {
                for (Class<?> c = abstractTexture.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        if (GpuTexture.class.isAssignableFrom(m.getReturnType())) {
                            m.setAccessible(true);
                            cachedGetGpuTextureMethod = m;
                            break;
                        }
                    }
                    if (cachedGetGpuTextureMethod != null) break;
                }
            }

            if (cachedGetGpuTextureMethod != null) {
                Object result = cachedGetGpuTextureMethod.invoke(abstractTexture);
                if (result instanceof GpuTexture gpu) {
                    cachedCursorGpuTexture = gpu;
                    return gpu;
                }
            }
        } catch (Exception e) {
            System.err.println("[NaturalMotionBlur] Failed to resolve cursor texture: " + e.getMessage());
        }
        return null;
    }

    private static void resolveTextureMethods(Object textureManager) {
        Class<?> resLocClass = ResourceLocation.class;
        for (Method m : textureManager.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isAssignableFrom(resLocClass)) continue;
            if (m.getReturnType().isPrimitive() || m.getReturnType() == void.class) continue;
            if (ResourceLocation.class.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            cachedGetTextureMethod = m;
            return;
        }
    }

    private static boolean registerTexture(Object textureManager) {
        try {
            Class<?> abstractTextureClass = cachedGetTextureMethod.getReturnType();
            for (Method m : textureManager.getClass().getMethods()) {
                if (m.getParameterCount() != 2) continue;
                if (m.getReturnType() != void.class) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(ResourceLocation.class)) continue;
                if (!m.getParameterTypes()[1].isAssignableFrom(abstractTextureClass)) continue;
                m.setAccessible(true);

                Object simpleTexture = createSimpleTexture(abstractTextureClass);
                if (simpleTexture == null) return false;

                m.invoke(textureManager, RecordingShaderManager.CURSOR_TEXTURE_ID, simpleTexture);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static Object createSimpleTexture(Class<?> abstractTextureClass) {
        try {
            var ctor = abstractTextureClass.getDeclaredConstructor(ResourceLocation.class);
            ctor.setAccessible(true);
            return ctor.newInstance(RecordingShaderManager.CURSOR_TEXTURE_ID);
        } catch (Exception ignored) {}
        return null;
    }

    private static void ensureTextureLoaded(Object texture, Minecraft mc) {
        try {
            Object resourceManager = mc.getResourceManager();
            for (Method m : texture.getClass().getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(resourceManager)
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    try { m.invoke(texture, resourceManager); } catch (Exception ignored) {}
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    // Frame Blending

    private static void applyIsolatedFrameBlending(RenderTarget main, float fps,
                                                   int refreshRate) {
        Minecraft mc = Minecraft.getInstance();
        updateLockedWindowSize(fps, refreshRate);

        pushHistoryFrame(main);

        int sampleCount = Math.min(recHistoryFilled, recLockedN);
        if (sampleCount <= 1) {
            recHasFirstFrame = true;
            return;
        }

        int oldestIndex = oldestHistoryIndex(sampleCount);

        recCombineChain = loadChain(mc, "frame_blending_recording");
        if (recCombineChain == null) { recHasFirstFrame = true; return; }

        PostPass pass = firstPass(recCombineChain);
        if (pass == null) { recHasFirstFrame = true; return; }

        RenderTarget fallback = recHistoryTargets[oldestIndex];
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        List<Integer> changedIndices = new ArrayList<>();
        List<PostPass.Input> originalInputs = new ArrayList<>();
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget src = (i < sampleCount)
                    ? recHistoryTargets[(oldestIndex + i) % MAX_HISTORY]
                    : fallback;
            int idx = findSamplerIndex(inputs, "Sample" + i);
            if (idx >= 0) {
                changedIndices.add(idx);
                originalInputs.add(inputs.get(idx));
                inputs.set(idx, new PersistentTextureInput("Sample" + i, src));
            }
        }

        try {
            float invSC = 1.0f / sampleCount;
            recCombineChain.process(main, savedAllocator, (RenderPass rp) -> {
                trySetUniform(rp, "invSampleCount", new float[]{invSC});
                trySetUniform(rp, new int[]{sampleCount});
            });
        } finally {
            for (int i = 0; i < changedIndices.size(); i++) {
                inputs.set(changedIndices.get(i), originalInputs.get(i));
            }
        }

        recHasFirstFrame = true;
    }

    // History management

    private static void updateLockedWindowSize(float fps, int refreshRate) {
        if (fps > 0.0f) {
            recSmoothedFPS = (recSmoothedFPS <= 0.0f) ? fps : recSmoothedFPS * 0.85f + fps * 0.15f;
        }
        int desired = 1;
        if (recSmoothedFPS > 0.0f && refreshRate > 0) {
            desired = Math.clamp(Math.round(recSmoothedFPS / refreshRate), 1, MAX_HISTORY);
        }
        if (desired == recLockedN) { recPendingN = desired; recPendingFrames = 0; return; }
        if (desired != recPendingN) { recPendingN = desired; recPendingFrames = 1; return; }
        recPendingFrames++;
        if (recPendingFrames >= WINDOW_CHANGE_HOLD_FRAMES) { recLockedN = recPendingN; recPendingFrames = 0; }
    }

    private static void pushHistoryFrame(RenderTarget src) {
        if (recHistoryTargets[recHistoryWriteIndex] == null) return;
        copyFramebuffer(src, recHistoryTargets[recHistoryWriteIndex]);
        recHistoryWriteIndex = (recHistoryWriteIndex + 1) % MAX_HISTORY;
        if (recHistoryFilled < MAX_HISTORY) recHistoryFilled++;
    }

    private static int oldestHistoryIndex(int sampleCount) {
        int idx = recHistoryWriteIndex - sampleCount;
        if (idx < 0) idx += MAX_HISTORY;
        return idx;
    }

    // Targets & copying

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

    public static void invalidateFrameBlending() {
        for (int i = 0; i < recHistoryTargets.length; i++) {
            if (recHistoryTargets[i] != null) {
                recHistoryTargets[i].destroyBuffers();
                recHistoryTargets[i] = null;
            }
        }
        recCombineChain = null;
        recCursorChain = null;
        recHasFirstFrame = false;
        recHistoryWriteIndex = 0;
        recHistoryFilled = 0;
        recLockedN = 1;
        recPendingN = 1;
        recPendingFrames = 0;
        recSmoothedFPS = 0.0f;
    }

    public static void destroy() {
        if (cleanFrameTarget != null) { cleanFrameTarget.destroyBuffers(); cleanFrameTarget = null; }
        savedAllocator = null;
        invalidateFrameBlending();
        lastW = 0;
        lastH = 0;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;
        cachedGetTextureMethod = null;
        cachedGetGpuTextureMethod = null;
        textureMethodsResolved = false;
        cachedCursorGpuTexture = null;
        lastSeenBlurAlgorithm = null;
        SpoutBridge.shutdown();
    }

    private static void copyFramebuffer(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        try {
            if (src.getColorTexture() == null || dst.getColorTexture() == null) return;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    src.getColorTexture(), dst.getColorTexture(),
                    0, 0, 0, 0, 0,
                    Math.min(src.width, dst.width), Math.min(src.height, dst.height));
        } catch (Exception e) {
            System.err.println("[NaturalMotionBlur] copyFramebuffer failed: " + e.getMessage());
        }
    }

    // PostChain helpers

    private static PostChain loadChain(Minecraft mc, String shaderPath) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) mc.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            return cache.getOrLoadPostChain(
                    ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderPath),
                    LevelTargetBundle.MAIN_TARGETS);
        } catch (Exception e) {
            return null;
        }
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static int findSamplerIndex(List<PostPass.Input> inputs, String samplerName) {
        for (int i = 0; i < inputs.size(); i++) {
            PostPass.Input input = inputs.get(i);
            if (input instanceof PostPass.TargetInput ti && samplerName.equals(ti.samplerName())) return i;
            if (input instanceof PostPass.TextureInput ti && samplerName.equals(ti.samplerName())) return i;
            if (input instanceof PersistentTextureInput pi && samplerName.equals(pi.samplerName)) return i;
            if (input instanceof GpuTextureInput gi && samplerName.equals(gi.samplerName)) return i;
        }
        return -1;
    }

    private static void setSampler(PostPass pass, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            PostPass.Input input = inputs.get(i);
            if (input instanceof PostPass.TargetInput ti && "Cursor".equals(ti.samplerName())) {
                inputs.set(i, replacement); return;
            }
            if (input instanceof PostPass.TextureInput ti && "Cursor".equals(ti.samplerName())) {
                inputs.set(i, replacement); return;
            }
            if (input instanceof PersistentTextureInput pi && "Cursor".equals(pi.samplerName)) {
                inputs.set(i, replacement); return;
            }
            if (input instanceof GpuTextureInput gi && "Cursor".equals(gi.samplerName)) {
                inputs.set(i, replacement); return;
            }
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
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            if (target.getColorTexture() != null) {
                pass.bindSampler(this.samplerName + "Sampler", target.getColorTexture());
            }
        }
    }

    private static class GpuTextureInput implements PostPass.Input {
        private final String samplerName;
        private final GpuTexture texture;

        GpuTextureInput(String samplerName, GpuTexture texture) {
            this.samplerName = samplerName;
            this.texture = texture;
        }

        @Override
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            pass.bindSampler(this.samplerName + "Sampler", texture);
        }
    }

    // Uniform helpers

    private static void trySetUniform(RenderPass pass, String name, float[] values) {
        try { pass.setUniform(name, values); } catch (Exception ignored) {}
    }

    private static void trySetUniform(RenderPass pass, int[] values) {
        try { pass.setUniform("activeCount", values); } catch (Exception ignored) {}
    }
}