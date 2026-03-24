package net.natural.motionblur;

import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.util.Identifier;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.ShaderLoaderAccessor;
import org.joml.Matrix4f;

public class ShaderManager {
    private static long lastNano;
    private static float currentBlur = 0.0f;
    private static float currentFPS = 0.0f;
    private static int sampleAmount = 100;

    // Cached per-frame matrix data set by MixinLevelRenderer
    private static final Matrix4f tempMvInverse      = new Matrix4f();
    private static final Matrix4f tempProjInverse    = new Matrix4f();
    private static final Matrix4f tempPrevModelView  = new Matrix4f();
    private static final Matrix4f tempPrevProjection = new Matrix4f();
    private static float camDX, camDY, camDZ;

    private static final Matrix4f scratchMatrix = new Matrix4f();
    private static boolean loadErrorLogged = false;
    private static ObjectAllocator frameAllocator = null;

    public static void captureAllocator(ObjectAllocator allocator) {
        frameAllocator = allocator;
    }

    public static void applyMotionBlur() {
        long now = System.nanoTime();
        float deltaTime = (now - lastNano) / 1_000_000_000.0f;
        lastNano = now;

            // FPS calculation
            if (deltaTime > 0 && deltaTime < 1.0f) {
                currentFPS = 1.0f / deltaTime;
            } else {
                currentFPS = 0.0f; // Avoid division by zero
            }

            if (shouldRenderMotionBlur()) {
                applyMotionBlurInternal();
            }
        frameAllocator = null;
    }

    // Checks if blur should be rendered
    private static boolean shouldRenderMotionBlur() {
        ConfigEntries config = ConfigManager.getConfig();
        // Config enabled?
        if (config.motionBlurStrength == 0 || !config.enabled) {
            return false;
        }
        // F5 enabled?
        MinecraftClient client = MinecraftClient.getInstance();
        return client.options.getPerspective().isFirstPerson() || config.renderF5;
    }

    private static void applyMotionBlurInternal() {
        ConfigEntries config = ConfigManager.getConfig();
        MinecraftClient client = MinecraftClient.getInstance();

        // Detect refresh rate on first use
        MonitorInfoProvider.updateDisplayInfo();
        int displayRefreshRate = MonitorInfoProvider.getRefreshRate();

        // Scale blur based on FPS vs refresh rate
        float baseStrength = config.motionBlurStrength;
        float scaledStrength = baseStrength;
        if (config.refreshRateScaling) {
            float fpsOverRefresh = (displayRefreshRate > 0) ? currentFPS / displayRefreshRate : 1.0f;
            if (fpsOverRefresh < 1.0f) fpsOverRefresh = 1.0f; // don't weaken blur under refresh rate
            scaledStrength = baseStrength * fpsOverRefresh;

            // Scale sample amount proportionally when FPS exceeds refresh rate
            if (fpsOverRefresh > 1.0f) {
                sampleAmount = (int) (100 * fpsOverRefresh);
            }
        }

        // Update strength if changed
        if (currentBlur != scaledStrength) {
            currentBlur = scaledStrength;
        }

        if (frameAllocator == null) return;

        PostEffectProcessor processor = getProcessor(client);
        if (processor == null) return;

        final float strength = scaledStrength;
        final int samples = sampleAmount;
        final int algorithm = config.blurAlgorithm.ordinal();
        final boolean depth = config.depthBlur;
        final float fw = client.getFramebuffer().textureWidth;
        final float fh = client.getFramebuffer().textureHeight;
        final float[] mvInv   = mat4ToArray(tempMvInverse);
        final float[] projInv = mat4ToArray(tempProjInverse);
        final float[] prevMV  = mat4ToArray(tempPrevModelView);
        final float[] prevP   = mat4ToArray(tempPrevProjection);
        final float dx = camDX, dy = camDY, dz = camDZ;

        processor.render(client.getFramebuffer(), frameAllocator, (RenderPass pass) -> {
            trySetUniform(pass, "mvInverse",         mvInv);
            trySetUniform(pass, "projInverse",       projInv);
            trySetUniform(pass, "prevModelView",     prevMV);
            trySetUniform(pass, "prevProjection",    prevP);
            trySetUniform(pass, "cameraDelta",       new float[]{dx, dy, dz});
            trySetUniform(pass, "view_res",          new float[]{fw, fh});
            trySetUniform(pass, "BlendFactor",       new float[]{strength});
            trySetUniform(pass, "motionBlurSamples", new int[]{samples});
            trySetUniform(pass, "blurAlgorithm",     new int[]{algorithm});
            trySetUniform(pass, "useDepth",          new int[]{depth ? 1 : 0});
        });
    }

    // Silently ignore uniforms that don't exist on the current pass
    private static void trySetUniform(RenderPass pass, String name, float[] values) {
        try { pass.setUniform(name, values); } catch (Exception ignored) {}
    }

    private static void trySetUniform(RenderPass pass, String name, int[] values) {
        try { pass.setUniform(name, values); } catch (Exception ignored) {}
    }

    private static float[] mat4ToArray(Matrix4f m) {
        float[] arr = new float[16];
        m.get(arr);
        return arr;
    }

    private static PostEffectProcessor getProcessor(MinecraftClient client) {
        try {
            ShaderLoader.Cache cache = ((ShaderLoaderAccessor) client.getShaderLoader()).getCache();
            if (cache == null) return null;
            loadErrorLogged = false;
            return cache.getOrLoadProcessor(
                    Identifier.of(NaturalMotionBlurMod.ID, "motion_blur"),
                    DefaultFramebufferSet.MAIN_ONLY);
        } catch (Exception e) {
            if (!loadErrorLogged) {
                System.err.println("[NaturalMotionBlur] Failed to load shader: " + e.getMessage());
                loadErrorLogged = true;
            }
            return null;
        }
    }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          float dx, float dy, float dz) {
        tempMvInverse.set(scratchMatrix.set(modelView).invert());
        tempProjInverse.set(scratchMatrix.set(projection).invert());
        tempPrevModelView.set(prevModelView);
        tempPrevProjection.set(prevProjection);
        camDX = dx; camDY = dy; camDZ = dz;
    }

    public static void updateBlurStrength(float strength) {
        currentBlur = strength;
    }
}