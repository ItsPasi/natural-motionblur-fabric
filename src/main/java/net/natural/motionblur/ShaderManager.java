package net.natural.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.LevelTargetBundle;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.resources.Identifier;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
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
    private static PostChain lastKnownProcessor = null; // Track processor identity to detect resource reloads (see replaceUniformBuffer)
    private static GraphicsResourceAllocator frameAllocator = null; // Captured each frame by MixinLevelRenderer; reset after use

    public static void captureAllocator(GraphicsResourceAllocator allocator) {
        frameAllocator = allocator;
    }

    public static void applyMotionBlur() {
        try {
            long now = System.nanoTime();
            float deltaTime = (now - lastNano) / 1_000_000_000.0f;
            lastNano = now;

            // FPS calculation
            if (deltaTime > 0 && deltaTime < 1.0f) {
                currentFPS = 1.0f / deltaTime;
            } else {
                currentFPS = 0.0f;
            }

            if (shouldRenderMotionBlur()) {
                applyMotionBlurInternal();
            }
        } finally {
            frameAllocator = null;
        }
    }

    // Checks if blur should be rendered
    private static boolean shouldRenderMotionBlur() {
        ConfigEntries config = ConfigManager.getConfig();
        return config.enabled && config.motionBlurStrength != 0;
    }

    private static void applyMotionBlurInternal() {
        ConfigEntries config = ConfigManager.getConfig();
        Minecraft client = Minecraft.getInstance();

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

        PostChain processor = getProcessor(client);
        if (processor == null) return;

        // Set uniform values for the shader
        replaceUniformBuffer(processor, scaledStrength,
                client.getMainRenderTarget().width,
                client.getMainRenderTarget().height,
                config.blurAlgorithm.ordinal());

        // Render the shader effect
        processor.process(client.getMainRenderTarget(), frameAllocator);
    }

    private static PostChain getProcessor(Minecraft client) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache = ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            loadErrorLogged = false;
            return cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "motion_blur"),
                    LevelTargetBundle.MAIN_TARGETS);
        } catch (Exception e) {
            if (!loadErrorLogged) {
                System.err.println("[NaturalMotionBlur] Failed to load shader: " + e.getMessage());
                loadErrorLogged = true;
            }
            return null;
        }
    }

    private static void replaceUniformBuffer(PostChain processor,
                                             float blendFactor, float viewW, float viewH,
                                             int blurAlgorithm) {
        List<PostPass> passes = ((PostChainAccessor) processor).getPasses();
        if (passes.isEmpty()) return;

        Map<String, GpuBuffer> uniformBuffers =
                ((PostPassAccessor) passes.getFirst()).getCustomUniforms();
        if (!uniformBuffers.containsKey("MotionBlurUniforms")) return;

        if (processor != lastKnownProcessor) {
            motionBlurUBO = null;
            lastKnownProcessor = processor;
        }

        if (motionBlurUBO == null) {
            motionBlurUBO = createBufferCompat();
            GpuBuffer old = uniformBuffers.put("MotionBlurUniforms", motionBlurUBO);
            if (old != null) old.close();
        }

        try (GpuBuffer.MappedView view = RenderSystem.getDevice()
                .createCommandEncoder()
                .mapBuffer(motionBlurUBO, false, true)) {
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
            builder.putInt(1); // useDepth always on
        }
    }

    private static GpuBuffer createBufferCompat() {
        Object device = RenderSystem.getDevice();
        java.util.function.Supplier<String> name = () -> "naturalmotionblur:MotionBlurUniforms";

        try {
            var m = device.getClass().getMethod(
                    "createBuffer", java.util.function.Supplier.class, int.class, long.class);
            return (GpuBuffer) m.invoke(device, name, 130, (long) ShaderManager.UBO_SIZE);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[NMB] createBuffer failed", e);
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