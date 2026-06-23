package net.natural.motionblur.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import net.natural.motionblur.NaturalMotionBlurMod;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
import net.natural.motionblur.util.ClientRenderTargets;
import net.natural.motionblur.util.GpuBufferUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecordingShaderManager {

    private static final int FRAME_BLEND_UBO_SIZE = 112;
    private static final int CURSOR_UBO_SIZE = 32;
    private static final int MAX_HISTORY = 24;
    private static final int GL_HISTORY_LIMIT = 12;
    private static final int UBO_RING_SIZE = 3;

    private static final String FRAME_BLEND_UBO = "FrameBlendParamsUniforms";

    private static final String[] SAMPLE_NAMES = new String[MAX_HISTORY];
    static {
        for (int i = 0; i < MAX_HISTORY; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static GraphicsResourceAllocator savedAllocator = null;

    private static RenderTarget cleanFrameTarget = null;
    private static RenderTarget recordingTarget  = null;
    private static final RenderTarget[] recHistoryTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] recHistoryInputs = new MutableTextureInput[MAX_HISTORY];
    private static final double[] recHistoryTimestamps = new double[MAX_HISTORY];
    private static final int[] recWeightedHistoryIndices = new int[MAX_HISTORY];
    private static final float[] recWeightedHistoryWeights = new float[MAX_HISTORY];
    private static final PostPass.Input[] savedSamplerScratch = new PostPass.Input[MAX_HISTORY];
    private static int lastW = 0;
    private static int lastH = 0;

    private static PostChain recCombineChain = null;
    private static final GpuBuffer[] recCombineUBORing = new GpuBuffer[UBO_RING_SIZE];
    private static int recCombineUBOIndex = 0;

    private static PostChain recCursorChain = null;
    private static GpuBuffer recCursorUBO   = null;

    private static final Identifier CURSOR_TEXTURE_ID =
            Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "textures/gui/obs_cursor.png");
    private static PostPass.Input recCursorTextureInput = null;

    private static boolean recHasFirstFrame        = false;
    private static int     recHistoryWriteIndex    = 0;
    private static int     recHistoryFilled        = 0;
    private static float   recSmoothedFPS          = 0;
    private static float   recPrevRawCursorX       = 0;
    private static float   recPrevRawCursorY       = 0;
    private static boolean recPrevRawCursorVisible = false;

    private static long    cachedGlfwHandle        = 0;
    private static boolean glfwHandleResolved      = false;

    private static boolean warnedCaptureFailure    = false;
    private static long    retryCaptureAfterNanos  = 0L;
    private static long    pauseCaptureUntilNanos  = 0L;
    private static boolean resourceReloadActive    = false;

    public static void captureAllocator(GraphicsResourceAllocator allocator) {
        savedAllocator = allocator;
    }

    public static void captureFinalFrameAndPresent() {
        long now = System.nanoTime();
        if (resourceReloadActive || (pauseCaptureUntilNanos > 0L && now < pauseCaptureUntilNanos)) {
            savedAllocator = null;
            return;
        }
        if (pauseCaptureUntilNanos > 0L) {
            pauseCaptureUntilNanos = 0L;
        }
        if (retryCaptureAfterNanos > 0L && now < retryCaptureAfterNanos) {
            savedAllocator = null;
            return;
        }

        try {
            captureFinalFrameAndPresentInternal();
        } catch (Throwable t) {
            handleCaptureFailure(t);
        }
    }

    private static void captureFinalFrameAndPresentInternal() {
        ConfigEntries cfg = ConfigManager.getConfig();
        if (!cfg.recordingOverlayEnabled) {
            savedAllocator = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(mc);
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            savedAllocator = null;
            return;
        }

        if (savedAllocator == null) {
            if (mc.level == null) {
                captureMenuFrameAndPresent(mc, main, w, h);
            }
            return;
        }

        try {
            ensureTargets(w, h);

            float realFps = ShaderManager.getCurrentFPS();
            int targetHz = Math.max(1, cfg.recordingOverlayTargetFPS);

            copyTexture(main, cleanFrameTarget);
            applyIsolatedFrameBlending(main, realFps, targetHz, w, h);

            if (recHasFirstFrame) {
                SpoutFrameSender.send(recordingTarget, w, h);
            }

            copyTexture(cleanFrameTarget, main);
        } finally {
            savedAllocator = null;
        }
    }

    private static void handleCaptureFailure(Throwable t) {
        savedAllocator = null;
        retryCaptureAfterNanos = System.nanoTime() + 1_000_000_000L;

        try {
            SpoutFrameSender.discardAfterFailure();
        } catch (Throwable ignored) {
        }
        discardRecordingStateWithoutRenderCleanup();

        if (!warnedCaptureFailure) {
            warnedCaptureFailure = true;
            System.err.println("[NMB] Spout capture was reset after a rendering/resource reload error: " + compactError(t));
        }
    }

    private static String compactError(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private static void captureMenuFrameAndPresent(Minecraft mc, RenderTarget main, int w, int h) {
        GraphicsResourceAllocator previousAllocator = savedAllocator;

        try {
            ensureTargets(w, h);
            copyTexture(main, cleanFrameTarget);

            savedAllocator = GraphicsResourceAllocator.UNPOOLED;
            applyCursorOverlay(main, mc, w, h);

            SpoutFrameSender.send(main, w, h);

            copyTexture(cleanFrameTarget, main);
        } finally {
            savedAllocator = previousAllocator;
        }
    }

    private static void applyIsolatedFrameBlending(RenderTarget main, float fps,
                                                   int refreshRate, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        updateSmoothedFPS(fps);

        applyCursorOverlay(main, mc, w, h);

        if (refreshRate <= 0) {
            recHistoryWriteIndex = 0;
            recHistoryFilled = 0;
            copyTexture(main, recordingTarget);
            recHasFirstFrame = true;
            return;
        }

        double now = currentTimeSeconds();
        pushHistoryFrame(main, now);

        int activeMaxHistory = activeFrameBlendSampleLimit();
        int sampleCount = buildWeightedSampleList(now, refreshRate, recSmoothedFPS, activeMaxHistory);
        if (sampleCount <= 1) {
            copyTexture(main, recordingTarget);
            recHasFirstFrame = true;
            return;
        }

        recCombineChain = loadChain(mc, recCombineChain, activeMaxHistory <= GL_HISTORY_LIMIT ? "frame_blending_gl" : "frame_blending");
        if (recCombineChain == null) { copyTexture(main, recordingTarget); recHasFirstFrame = true; return; }
        PostPass combinePass = firstPass(recCombineChain);
        if (combinePass == null) { copyTexture(main, recordingTarget); recHasFirstFrame = true; return; }
        Map<String, GpuBuffer> combineUniforms = ((PostPassAccessor) combinePass).getCustomUniforms();
        if (!combineUniforms.containsKey(FRAME_BLEND_UBO)) {
            copyTexture(main, recordingTarget); recHasFirstFrame = true; return;
        }

        GpuBuffer recCombineUBO = nextRecCombineUBO();
        GpuBuffer savedCombineUBO = combineUniforms.put(FRAME_BLEND_UBO, recCombineUBO);
        writeBlendParamsUBO(recCombineUBO, inverseTotalWeight(sampleCount), sampleCount);

        RenderTarget fallback = recHistoryTargets[recWeightedHistoryIndices[sampleCount - 1]];
        try {
            for (int i = 0; i < activeMaxHistory; i++) {
                RenderTarget target = (i < sampleCount)
                        ? recHistoryTargets[recWeightedHistoryIndices[i]]
                        : fallback;
                MutableTextureInput input = recHistoryInputs[i];
                if (input == null) {
                    input = new MutableTextureInput(SAMPLE_NAMES[i], target);
                    recHistoryInputs[i] = input;
                } else {
                    input.setTarget(target);
                }
                savedSamplerScratch[i] = swapSampler(combinePass, SAMPLE_NAMES[i], input);
            }

            recCombineChain.process(main, savedAllocator);
        } finally {
            if (savedCombineUBO != null) {
                combineUniforms.put(FRAME_BLEND_UBO, savedCombineUBO);
            } else {
                combineUniforms.remove(FRAME_BLEND_UBO);
            }

            for (int i = 0; i < activeMaxHistory; i++) {
                if (savedSamplerScratch[i] != null) {
                    swapSampler(combinePass, SAMPLE_NAMES[i], savedSamplerScratch[i]);
                    savedSamplerScratch[i] = null;
                }
            }
        }

        copyTexture(main, recordingTarget);
        recHasFirstFrame = true;
    }

    private static void updateSmoothedFPS(float fps) {
        if (fps > 0.0f) {
            recSmoothedFPS = (recSmoothedFPS <= 0.0f) ? fps : recSmoothedFPS * 0.85f + fps * 0.15f;
        }
    }

    private static void pushHistoryFrame(RenderTarget src, double timestamp) {
        if (recHistoryTargets[recHistoryWriteIndex] == null) return;
        copyTexture(src, recHistoryTargets[recHistoryWriteIndex]);
        recHistoryTimestamps[recHistoryWriteIndex] = timestamp;
        recHistoryWriteIndex = (recHistoryWriteIndex + 1) % MAX_HISTORY;
        if (recHistoryFilled < MAX_HISTORY) recHistoryFilled++;
    }

    private static int buildWeightedSampleList(double exposureEnd, int refreshRate, float fps, int maxSamples) {
        Arrays.fill(recWeightedHistoryWeights, 0.0f);
        if (recHistoryFilled <= 0) return 0;

        double exposureStart = exposureEnd - (1.0 / refreshRate);
        double estimatedFrameTime = (fps > 0.0f) ? 1.0 / fps : 1.0 / refreshRate;
        double totalWeight = 0.0;
        int sampleCount = 0;
        int firstIndex = recHistoryWriteIndex - recHistoryFilled;
        if (firstIndex < 0) firstIndex += MAX_HISTORY;

        for (int i = 0; i < recHistoryFilled; i++) {
            int idx = (firstIndex + i) % MAX_HISTORY;
            double frameEnd = recHistoryTimestamps[idx];
            if (frameEnd <= 0.0) continue;

            double frameStart;
            if (i > 0) {
                int prevIdx = (firstIndex + i - 1) % MAX_HISTORY;
                frameStart = recHistoryTimestamps[prevIdx];
            } else {
                frameStart = frameEnd - estimatedFrameTime;
            }
            if (frameStart >= frameEnd) frameStart = frameEnd - estimatedFrameTime;

            double overlap = Math.min(frameEnd, exposureEnd) - Math.max(frameStart, exposureStart);
            if (overlap > 0.0000001) {
                recWeightedHistoryIndices[sampleCount] = idx;
                recWeightedHistoryWeights[sampleCount] = (float)overlap;
                totalWeight += overlap;
                sampleCount++;
            }
        }

        if (sampleCount <= 0) return 0;
        if (totalWeight <= 0.0000001) return 0;

        if (sampleCount > maxSamples) {
            int offset = sampleCount - maxSamples;
            for (int i = 0; i < maxSamples; i++) {
                recWeightedHistoryIndices[i] = recWeightedHistoryIndices[offset + i];
                recWeightedHistoryWeights[i] = recWeightedHistoryWeights[offset + i];
            }
            for (int i = maxSamples; i < sampleCount; i++) {
                recWeightedHistoryWeights[i] = 0.0f;
            }
            sampleCount = maxSamples;
        }

        return sampleCount;
    }

    private static float inverseTotalWeight(int sampleCount) {
        float totalWeight = 0.0f;
        for (int i = 0; i < sampleCount; i++) {
            totalWeight += recWeightedHistoryWeights[i];
        }
        return totalWeight > 0.0f ? 1.0f / totalWeight : 1.0f;
    }

    private static double currentTimeSeconds() {
        return System.nanoTime() * 1.0E-9;
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
        if (recCursorChain == null) return;
        PostPass cursorPass = firstPass(recCursorChain);
        if (cursorPass == null) return;

        Map<String, GpuBuffer> cursorUniforms = ((PostPassAccessor) cursorPass).getCustomUniforms();
        if (!cursorUniforms.containsKey("CursorOverlayUniforms")) return;

        if (recCursorUBO == null) recCursorUBO = GpuBufferUtil.createUBO("CursorOverlayUniforms", CURSOR_UBO_SIZE);
        GpuBuffer savedCursorUBO = cursorUniforms.put("CursorOverlayUniforms", recCursorUBO);
        PostPass.Input savedCursorSampler = null;

        try {
            writeCursorUBO(recCursorUBO, cursor.x, cursor.y, cursor.scale, prevDrawX, prevDrawY);

            if (recCursorTextureInput == null) {
                recCursorTextureInput = new ResourceTextureInput("Cursor", CURSOR_TEXTURE_ID);
            }

            GpuTextureView testView = getTextureView(mc, CURSOR_TEXTURE_ID);
            if (testView == null) return;

            savedCursorSampler = swapSampler(cursorPass, "Cursor", recCursorTextureInput);
            recCursorChain.process(target, savedAllocator);

            recPrevRawCursorX = cursor.x;
            recPrevRawCursorY = cursor.y;
            recPrevRawCursorVisible = true;
        } finally {
            if (savedCursorUBO != null) {
                cursorUniforms.put("CursorOverlayUniforms", savedCursorUBO);
            } else {
                cursorUniforms.remove("CursorOverlayUniforms");
            }
            if (savedCursorSampler != null) swapSampler(cursorPass, "Cursor", savedCursorSampler);
        }
    }

    private static void writeCursorUBO(GpuBuffer ubo,
                                       float cursorX, float cursorY, float cursorScale,
                                       float prevCursorX, float prevCursorY) {
        GpuBufferUtil.writeStd140(ubo, CURSOR_UBO_SIZE, b -> {
            b.putFloat(cursorX);
            b.putFloat(cursorY);
            b.putFloat(cursorScale);
            b.putFloat(1.0f);
            b.putFloat(prevCursorX);
            b.putFloat(prevCursorY);
            b.putFloat(1.0f);
            b.putFloat(0.0f);
        });
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

            float x = (float) (xArr[0] * renderWidth / winW);
            float y = (float) (yArr[0] * renderHeight / winH);
            if (x < -32 || y < -32 || x > renderWidth + 32 || y > renderHeight + 32)
                return CursorState.HIDDEN;

            return new CursorState(x, y, 1.0f, true);
        } catch (Throwable ignored) {
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
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        glfwHandleResolved = true;
        return 0;
    }

    private record CursorState(float x, float y, float scale, boolean visible) {
        private static final CursorState HIDDEN = new CursorState(0.0f, 0.0f, 1.0f, false);
    }

    public static void invalidateFrameBlending() {
        for (int i = 0; i < recHistoryTargets.length; i++) {
            if (recHistoryTargets[i] != null) {
                destroyTargetBuffers(recHistoryTargets[i]);
                recHistoryTargets[i] = null;
            }
            recHistoryInputs[i] = null;
            recHistoryTimestamps[i] = 0.0;
            savedSamplerScratch[i] = null;
        }
        closeRecCombineUBORing();
        GpuBufferUtil.closeQuietly(recCursorUBO);
        recCursorUBO = null;
        recCombineChain = null;
        recCursorChain = null;
        recCursorTextureInput = null;
        recHasFirstFrame = false;
        recHistoryWriteIndex = 0;
        recHistoryFilled = 0;
        recSmoothedFPS = 0.0f;
        Arrays.fill(recWeightedHistoryIndices, 0);
        Arrays.fill(recWeightedHistoryWeights, 0.0f);
        recPrevRawCursorVisible = false;
    }


    public static void beginResourceReload() {
        resourceReloadActive = true;
        pauseOutput();
        pauseCaptureUntilNanos = Long.MAX_VALUE;
        SpoutFrameSender.beginResourceReload();
    }

    public static void endResourceReload() {
        resourceReloadActive = false;
        pauseOutput();
        pauseCaptureUntilNanos = System.nanoTime() + 1_000_000_000L;
        SpoutFrameSender.endResourceReload();
    }


    public static void pauseOutput() {
        savedAllocator = null;
        retryCaptureAfterNanos = 0L;
        recHasFirstFrame = false;
        recHistoryWriteIndex = 0;
        recHistoryFilled = 0;
        recSmoothedFPS = 0.0f;
        Arrays.fill(recWeightedHistoryIndices, 0);
        Arrays.fill(recWeightedHistoryWeights, 0.0f);
        recPrevRawCursorVisible = false;
    }

    @SuppressWarnings("unused")
    public static void destroyOnRenderThread() {
        if (isRenderThreadSafe()) {
            destroy();
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(RecordingShaderManager::destroy);
            return;
        } catch (Throwable ignored) {
        }

        destroyFromAnyThread();
    }

    public static void destroyFromAnyThread() {
        if (isRenderThreadSafe()) {
            destroy();
            return;
        }

        try {
            SpoutFrameSender.shutdownWithoutRenderThreadCleanup();
        } catch (Throwable ignored) {
        }
        discardRecordingStateWithoutRenderCleanup();
    }

    public static void destroy() {
        if (!isRenderThreadSafe()) {
            destroyFromAnyThread();
            return;
        }

        SpoutFrameSender.shutdown();
        savedAllocator = null;
        retryCaptureAfterNanos = 0L;
        pauseCaptureUntilNanos = 0L;
        resourceReloadActive = false;
        warnedCaptureFailure = false;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;

        if (cleanFrameTarget != null) { destroyTargetBuffers(cleanFrameTarget); cleanFrameTarget = null; }
        if (recordingTarget != null) { destroyTargetBuffers(recordingTarget); recordingTarget = null; }
        invalidateFrameBlending();
        lastW = 0;
        lastH = 0;
    }

    private static boolean isRenderThreadSafe() {
        try {
            return RenderSystem.isOnRenderThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void discardRecordingStateWithoutRenderCleanup() {
        savedAllocator = null;
        cleanFrameTarget = null;
        recordingTarget = null;
        cachedGlfwHandle = 0;
        glfwHandleResolved = false;
        for (int i = 0; i < recHistoryTargets.length; i++) {
            recHistoryTargets[i] = null;
            recHistoryInputs[i] = null;
            recHistoryTimestamps[i] = 0.0;
            savedSamplerScratch[i] = null;
        }
        Arrays.fill(recCombineUBORing, null);
        recCursorUBO = null;
        recCombineChain = null;
        recCursorChain = null;
        recCursorTextureInput = null;
        recHasFirstFrame = false;
        recHistoryWriteIndex = 0;
        recHistoryFilled = 0;
        recSmoothedFPS = 0.0f;
        Arrays.fill(recWeightedHistoryIndices, 0);
        Arrays.fill(recWeightedHistoryWeights, 0.0f);
        recPrevRawCursorVisible = false;
        lastW = 0;
        lastH = 0;
    }

    private static void ensureTargets(int w, int h) {
        if (cleanFrameTarget != null && recordingTarget != null && lastW == w && lastH == h && recHistoryTargets[0] != null) return;
        if (cleanFrameTarget != null) destroyTargetBuffers(cleanFrameTarget);
        if (recordingTarget != null) destroyTargetBuffers(recordingTarget);
        cleanFrameTarget = new MainTarget(w, h);
        recordingTarget = new MainTarget(w, h);
        invalidateFrameBlending();
        for (int i = 0; i < recHistoryTargets.length; i++) {
            recHistoryTargets[i] = new MainTarget(w, h);
            recHistoryTimestamps[i] = 0.0;
        }
        lastW = w;
        lastH = h;
    }

    private static void destroyTargetBuffers(RenderTarget target) {
        try {
            target.destroyBuffers();
        } catch (Throwable ignored) {
        }
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        assert src.getColorTexture() != null;
        assert dst.getColorTexture() != null;
        int copyW = Math.min(src.width, dst.width);
        int copyH = Math.min(src.height, dst.height);
        if (copyW <= 0 || copyH <= 0) return;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, copyW, copyH);
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

    private static int activeFrameBlendSampleLimit() {
        return isOpenGlBackend() ? GL_HISTORY_LIMIT : MAX_HISTORY;
    }

    private static boolean isOpenGlBackend() {
        try {
            Object device = RenderSystem.getDevice();
            String name = device.getClass().getName().toLowerCase(Locale.ROOT);
            Object backend = findBackend(device);
            if (backend != null) name += " " + backend.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("vulkan")) return false;
            return name.contains("opengl") || name.contains("gl");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findBackend(Object device) {
        for (Class<?> c = device.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("backend");
                field.setAccessible(true);
                return field.get(device);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static GpuBuffer nextRecCombineUBO() {
        GpuBuffer ubo = recCombineUBORing[recCombineUBOIndex];
        if (ubo == null) {
            ubo = GpuBufferUtil.createUBO("RecFrameBlendParamsUniforms", FRAME_BLEND_UBO_SIZE);
            recCombineUBORing[recCombineUBOIndex] = ubo;
        }
        recCombineUBOIndex = (recCombineUBOIndex + 1) % UBO_RING_SIZE;
        return ubo;
    }

    private static void closeRecCombineUBORing() {
        for (int i = 0; i < recCombineUBORing.length; i++) {
            if (recCombineUBORing[i] != null) {
                GpuBufferUtil.closeQuietly(recCombineUBORing[i]);
                recCombineUBORing[i] = null;
            }
        }
        recCombineUBOIndex = 0;
    }

    private static PostChain loadChain(Minecraft mc, PostChain cached, String shaderPath) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) mc.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain result = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderPath),
                    LevelTargetBundle.MAIN_TARGETS);
            if (result != cached) {
                switch (shaderPath) {
                    case "frame_blending", "frame_blending_gl" -> closeRecCombineUBORing();
                    case "cursor_overlay" -> {
                        GpuBufferUtil.closeQuietly(recCursorUBO);
                        recCursorUBO = null;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            System.err.println("[NaturalMotionBlur] Failed to load recording chain " + shaderPath + ": " + e.getMessage());
            return null;
        }
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, float invTotalWeight, int sampleCount) {
        GpuBufferUtil.writeStd140(ubo, FRAME_BLEND_UBO_SIZE, b -> {
            b.putFloat(invTotalWeight);
            b.putInt(sampleCount);
            for (int i = 0; i < MAX_HISTORY; i++) {
                b.putFloat(i < sampleCount ? recWeightedHistoryWeights[i] : 0.0f);
            }
            b.putFloat(0.0f);
            b.putFloat(0.0f);
        });
    }

    private static class MutableTextureInput implements PostPass.Input {
        private final String samplerName;
        private RenderTarget target;

        MutableTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target = target;
        }

        void setTarget(RenderTarget target) {
            this.target = target;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @NonNull GpuTextureView texture(
                @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            assert target.getColorTextureView() != null;
            return target.getColorTextureView();
        }

        @Override public @NonNull String samplerName() { return samplerName; }
        @Override public boolean bilinear() { return false; }
    }

    private static class ResourceTextureInput implements PostPass.Input {
        private final String samplerName;
        private final Identifier textureId;

        ResourceTextureInput(String samplerName, Identifier textureId) {
            this.samplerName = samplerName;
            this.textureId = textureId;
        }

        @Override
        public void addToPass(com.mojang.blaze3d.framegraph.@NonNull FramePass pass,
                              @NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @Nullable GpuTextureView texture(@NonNull Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<RenderTarget>> targets) {
            Minecraft mc = Minecraft.getInstance();
            return getTextureView(mc, textureId);
        }

        @Override public @NonNull String samplerName() { return samplerName; }
        @Override public boolean bilinear() { return true; }
    }

    private static GpuTextureView getTextureView(Minecraft mc, Identifier textureId) {
        try {
            return mc.getTextureManager().getTexture(textureId).getTextureView();
        } catch (Throwable ignored) {
        }
        return null;
    }
}