package net.natural.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPass;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.util.Identifier;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.PostEffectPassAccessor;
import net.natural.motionblur.mixin.PostEffectProcessorAccessor;
import net.natural.motionblur.mixin.ShaderLoaderAccessor;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

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
    private static GpuBuffer motionBlurUBO = null;
    private static final int UBO_SIZE = 304;
    private static boolean loadErrorLogged = false;
    private static PostEffectProcessor lastKnownProcessor = null; // Track processor identity to detect resource reloads (see replaceUniformBuffer)
    private static ObjectAllocator frameAllocator = null; // Captured each frame by MixinLevelRenderer; reset after use

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

        // Set uniform values for the shader
        replaceUniformBuffer(processor, scaledStrength,
                client.getFramebuffer().textureWidth,
                client.getFramebuffer().textureHeight,
                config.depthBlur, config.blurAlgorithm.ordinal());

        // Render the shader effect
        processor.render(client.getFramebuffer(), frameAllocator);
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

    private static void replaceUniformBuffer(PostEffectProcessor processor,
                                             float blendFactor, float viewW, float viewH,
                                             boolean useDepth, int blurAlgorithm) {
        List<PostEffectPass> passes = ((PostEffectProcessorAccessor) processor).getPasses();
        if (passes.isEmpty()) return;

        Map<String, GpuBuffer> uniformBuffers =
                ((PostEffectPassAccessor) passes.getFirst()).getUniformBuffers();
        if (!uniformBuffers.containsKey("MotionBlurUniforms")) return;

        // Prevent Resource Reload Crash
        if (processor != lastKnownProcessor) {
            motionBlurUBO = null;
            lastKnownProcessor = processor;
        }

        if (motionBlurUBO == null) {
            motionBlurUBO = createBufferCompat(
            );
            GpuBuffer old = uniformBuffers.put("MotionBlurUniforms", motionBlurUBO);
            if (old != null) old.close();
        }

        // Map and write uniform data directly into the existing GPU buffer
        try (GpuBuffer.MappedView view = RenderSystem.getDevice()
                .createCommandEncoder()
                .mapBuffer(motionBlurUBO, false, true)) {
            if (view == null) return;
            Std140Builder builder = Std140Builder.intoBuffer(view.data());
            builder.putMat4f(tempMvInverse);
            builder.putMat4f(tempProjInverse);
            builder.putMat4f(tempPrevModelView);
            builder.putMat4f(tempPrevProjection);
            builder.putVec3(camDX, camDY, camDZ);
            builder.putVec2(viewW, viewH);
            builder.putFloat(blendFactor);
            builder.putInt(sampleAmount);
            builder.putInt(blurAlgorithm);
            builder.putInt(useDepth ? 1 : 0);
        }
    }

    private static GpuBuffer createBufferCompat() {
        Object device = RenderSystem.getDevice();
        java.util.function.Supplier<String> name = () -> "naturalmotionblur:MotionBlurUniforms";

        // 1.21.9: createBuffer(Supplier<String>, int, int)
        try {
            var m = device.getClass().getMethod(
                    "createBuffer", java.util.function.Supplier.class, int.class, int.class);
            return (GpuBuffer) m.invoke(device, name, 130, ShaderManager.UBO_SIZE);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[NMB] createBuffer (1.21.9) failed", e);
        }

        // 1.21.11: createBuffer(Supplier<String>, int, long)
        try {
            var m = device.getClass().getMethod(
                    "createBuffer", java.util.function.Supplier.class, int.class, long.class);
            return (GpuBuffer) m.invoke(device, name, 130, (long) ShaderManager.UBO_SIZE);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[NMB] createBuffer (1.21.11) failed", e);
        }

        throw new RuntimeException("[NMB] No compatible createBuffer found on " + device.getClass());
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