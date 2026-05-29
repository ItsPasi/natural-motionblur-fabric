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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class RecordingShaderManager {

    private static final int MAX_HISTORY = 12;
    private static final String[] SAMPLE_NAMES = new String[MAX_HISTORY];
    static {
        for (int i = 0; i < MAX_HISTORY; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static GraphicsResourceAllocator savedAllocator = null;

    private static RenderTarget cleanFrameTarget = null;
    private static final RenderTarget[] recHistoryTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] recHistoryInputs = new MutableTextureInput[MAX_HISTORY];
    private static final double[] recHistoryTimestamps = new double[MAX_HISTORY];
    private static final int[] recWeightedHistoryIndices = new int[MAX_HISTORY];
    private static final float[] recWeightedHistoryWeights = new float[MAX_HISTORY];
    private static final int[] changedSamplerIndices = new int[MAX_HISTORY];
    private static final PostPass.Input[] savedSamplerInputs = new PostPass.Input[MAX_HISTORY];
    private static int lastW = 0;
    private static int lastH = 0;

    private static int rawSpoutTexture = 0;
    private static int rawSpoutW = 0;
    private static int rawSpoutH = 0;

    // Frame blending
    private static PostChain recCombineChain = null;
    private static boolean recHasFirstFrame        = false;
    private static int     recHistoryWriteIndex    = 0;
    private static int     recHistoryFilled        = 0;
    private static float   recSmoothedFPS          = 0;

    // Cursor overlay
    private static PostChain recCursorChain = null;
    private static final ResourceLocation CURSOR_TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "textures/gui/obs_cursor.png");
    private static GpuTextureInput recCursorTextureInput = null;
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
            if (!cfg.recordingOverlayEnabled) return;

            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width;
            int h = main.height;
            if (w <= 0 || h <= 0) return;

            if (savedAllocator == null) {
                captureMenuFrameAndPresent(mc, main, w, h);
                return;
            }

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
                blitMainToScreenAndSendSpout(main, w, h);
            }

            copyFramebuffer(cleanFrameTarget, main);
            main.blitToScreen();
        } finally {
            savedAllocator = null;
        }
    }

    private static void captureMenuFrameAndPresent(Minecraft mc, RenderTarget main, int w, int h) {
        GraphicsResourceAllocator previousAllocator = savedAllocator;

        try {
            ensureTargets(w, h);
            copyFramebuffer(main, cleanFrameTarget);

            savedAllocator = GraphicsResourceAllocator.UNPOOLED;
            applyCursorOverlay(main, mc, w, h);

            blitMainToScreenAndSendSpout(main, w, h);
            copyFramebuffer(cleanFrameTarget, main);
            main.blitToScreen();
        } finally {
            savedAllocator = previousAllocator;
        }
    }

    private static void blitMainToScreenAndSendSpout(RenderTarget main, int w, int h) {
        main.blitToScreen();
        sendCurrentFramebufferToSpout(w, h);
    }

    private static void sendCurrentFramebufferToSpout(int w, int h) {
        if (w <= 0 || h <= 0) return;

        RenderSystem.assertOnRenderThread();

        ensureRawSpoutTexture(w, h);
        if (rawSpoutTexture == 0) return;

        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        int[] oldViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);

        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, rawSpoutTexture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            GL11.glFlush();
            SpoutBridge.sendTexture(rawSpoutTexture, w, h);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
        }
    }

    private static void ensureRawSpoutTexture(int w, int h) {
        if (rawSpoutTexture != 0 && rawSpoutW == w && rawSpoutH == h) {
            return;
        }

        destroyRawSpoutTexture();

        rawSpoutW = w;
        rawSpoutH = h;

        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        rawSpoutTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rawSpoutTexture);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                w,
                h,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null
        );

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
    }

    private static void destroyRawSpoutTexture() {
        if (rawSpoutTexture != 0) {
            GL11.glDeleteTextures(rawSpoutTexture);
            rawSpoutTexture = 0;
        }
        rawSpoutW = 0;
        rawSpoutH = 0;
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

        if (recCursorTextureInput == null) {
            recCursorTextureInput = new GpuTextureInput("Cursor", cursorTex);
        } else {
            recCursorTextureInput.setTexture(cursorTex);
        }
        setSampler(cursorPass, recCursorTextureInput);

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
        updateSmoothedFPS(fps);

        if (refreshRate <= 0) {
            recHistoryWriteIndex = 0;
            recHistoryFilled = 0;
            recHasFirstFrame = true;
            return;
        }

        double now = currentTimeSeconds();
        pushHistoryFrame(main, now);

        int sampleCount = buildWeightedSampleList(now, refreshRate, recSmoothedFPS);
        if (sampleCount <= 1) {
            recHasFirstFrame = true;
            return;
        }

        recCombineChain = loadChain(mc, "frame_blending_recording");
        if (recCombineChain == null) { recHasFirstFrame = true; return; }

        PostPass pass = firstPass(recCombineChain);
        if (pass == null) { recHasFirstFrame = true; return; }

        RenderTarget fallback = recHistoryTargets[recWeightedHistoryIndices[sampleCount - 1]];
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        int changedCount = 0;
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget src = (i < sampleCount)
                    ? recHistoryTargets[recWeightedHistoryIndices[i]]
                    : fallback;
            int idx = findSamplerIndex(inputs, SAMPLE_NAMES[i]);
            if (idx >= 0) {
                MutableTextureInput input = recHistoryInputs[i];
                if (input == null) {
                    input = new MutableTextureInput(SAMPLE_NAMES[i], src);
                    recHistoryInputs[i] = input;
                } else {
                    input.setTarget(src);
                }
                changedSamplerIndices[changedCount] = idx;
                savedSamplerInputs[changedCount] = inputs.get(idx);
                inputs.set(idx, input);
                changedCount++;
            }
        }

        try {
            float invTotalWeight = inverseTotalWeight(sampleCount);
            recCombineChain.process(main, savedAllocator, (RenderPass rp) -> {
                trySetUniform(rp, "invTotalWeight", new float[]{invTotalWeight});
                trySetUniform(rp, new int[]{sampleCount});
                setSampleWeightUniforms(rp, sampleCount);
            });
        } finally {
            for (int i = 0; i < changedCount; i++) {
                inputs.set(changedSamplerIndices[i], savedSamplerInputs[i]);
                savedSamplerInputs[i] = null;
            }
        }

        recHasFirstFrame = true;
    }

    // History management

    private static void updateSmoothedFPS(float fps) {
        if (fps > 0.0f) {
            recSmoothedFPS = (recSmoothedFPS <= 0.0f) ? fps : recSmoothedFPS * 0.85f + fps * 0.15f;
        }
    }

    private static void pushHistoryFrame(RenderTarget src, double timestamp) {
        if (recHistoryTargets[recHistoryWriteIndex] == null) return;
        copyFramebuffer(src, recHistoryTargets[recHistoryWriteIndex]);
        recHistoryTimestamps[recHistoryWriteIndex] = timestamp;
        recHistoryWriteIndex = (recHistoryWriteIndex + 1) % MAX_HISTORY;
        if (recHistoryFilled < MAX_HISTORY) recHistoryFilled++;
    }

    private static int buildWeightedSampleList(double exposureEnd, int refreshRate, float fps) {
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

    // Targets & copying

    private static void ensureTargets(int w, int h) {
        if (cleanFrameTarget != null && lastW == w && lastH == h && recHistoryTargets[0] != null) return;
        if (cleanFrameTarget != null) cleanFrameTarget.destroyBuffers();
        cleanFrameTarget = new MainTarget(w, h);
        invalidateFrameBlending();
        for (int i = 0; i < recHistoryTargets.length; i++) {
            recHistoryTargets[i] = new MainTarget(w, h);
            recHistoryTimestamps[i] = 0.0;
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
            recHistoryInputs[i] = null;
            recHistoryTimestamps[i] = 0.0;
            savedSamplerInputs[i] = null;
        }
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

    public static void destroy() {
        if (RenderSystem.isOnRenderThread()) {
            destroyRawSpoutTexture();
        }
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
            if (input instanceof MutableTextureInput pi && samplerName.equals(pi.samplerName)) return i;
            if (input instanceof GpuTextureInput gi && samplerName.equals(gi.samplerName)) return i;
        }
        return -1;
    }

    private static void setSampler(PostPass pass, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            PostPass.Input input = inputs.get(i);
            if (input instanceof PostPass.TargetInput ti && "Cursor".equals(ti.samplerName())) {
                if (input != replacement) inputs.set(i, replacement); return;
            }
            if (input instanceof PostPass.TextureInput ti && "Cursor".equals(ti.samplerName())) {
                if (input != replacement) inputs.set(i, replacement); return;
            }
            if (input instanceof MutableTextureInput pi && "Cursor".equals(pi.samplerName)) {
                if (input != replacement) inputs.set(i, replacement); return;
            }
            if (input instanceof GpuTextureInput gi && "Cursor".equals(gi.samplerName)) {
                if (input != replacement) inputs.set(i, replacement); return;
            }
        }
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
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            if (target != null && target.getColorTexture() != null) {
                pass.bindSampler(this.samplerName + "Sampler", target.getColorTexture());
            }
        }
    }

    private static class GpuTextureInput implements PostPass.Input {
        private final String samplerName;
        private GpuTexture texture;

        GpuTextureInput(String samplerName, GpuTexture texture) {
            this.samplerName = samplerName;
            this.texture = texture;
        }

        void setTexture(GpuTexture texture) {
            this.texture = texture;
        }

        @Override
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            if (texture != null) {
                pass.bindSampler(this.samplerName + "Sampler", texture);
            }
        }
    }

    // Uniform helpers


    private static void setSampleWeightUniforms(RenderPass pass, int sampleCount) {
        for (int i = 0; i < MAX_HISTORY; i++) {
            float weight = i < sampleCount ? RecordingShaderManager.recWeightedHistoryWeights[i] : 0.0f;
            trySetUniform(pass, SAMPLE_NAMES[i] + "Weight", new float[]{weight});
        }
    }

    private static void trySetUniform(RenderPass pass, String name, float[] values) {
        try { pass.setUniform(name, values); } catch (Exception ignored) {}
    }

    private static void trySetUniform(RenderPass pass, int[] values) {
        try { pass.setUniform("activeCount", values); } catch (Exception ignored) {}
    }
}