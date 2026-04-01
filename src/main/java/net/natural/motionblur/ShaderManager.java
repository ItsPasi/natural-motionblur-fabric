package net.natural.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
import net.natural.motionblur.shader.BlurStrengthCalculator;
import net.natural.motionblur.shader.CameraState;
import net.natural.motionblur.shader.FrameAccumulationManager;
import net.natural.motionblur.shader.FrameTimer;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class ShaderManager {

    private static final FrameTimer             frameTimer  = new FrameTimer();
    private static final CameraState            cameraState = new CameraState();
    private static final BlurStrengthCalculator strengthCalc = new BlurStrengthCalculator();

    private static GraphicsResourceAllocator frameAllocator = null;

    private static PostChain cachedPreProcessor  = null;
    private static PostChain cachedF5Processor   = null;
    private static PostChain cachedPostProcessor = null;

    private static boolean preLoadErrorLogged  = false;
    private static boolean f5LoadErrorLogged   = false;
    private static boolean postLoadErrorLogged = false;

    private static final int UBO_SIZE = 304;
    private static GpuBuffer preEntityUBO      = null;
    private static GpuBuffer postRenderUBO     = null;
    private static Method    createBufferMethod = null; // reflection cache for GpuDevice.createBuffer()

    private enum BlurPass { NORMAL_PRE, SPECIAL_F5, NORMAL_POST }

    public static void captureAllocator(GraphicsResourceAllocator allocator) { frameAllocator = allocator; }
    public static void clearFrameAllocator() { frameAllocator = null; }
    public static void beginFrame() { frameTimer.beginFrame(); }

    // Called on window resize — clears accumulation state so it rebuilds at the new resolution
    public static void invalidate() { FrameAccumulationManager.invalidate(); }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          float dx, float dy, float dz) {
        cameraState.setFrame(modelView, prevModelView, projection, prevProjection, dx, dy, dz);
    }

    public static void applyPreEntityBlur()    { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_PRE);  }
    public static void applyF5EntityRideBlur() { if (shouldRun()) applyBlurInternal(BlurPass.SPECIAL_F5);  }
    public static void applyPostRenderBlur()   { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_POST); }

    private static boolean shouldRun() {
        ConfigEntries config = ConfigManager.getConfig();
        return config.enabled && config.motionBlurStrength != 0;
    }

    private static void applyBlurInternal(BlurPass pass) {
        if (frameAllocator == null) return;

        ConfigEntries config = ConfigManager.getConfig();
        Minecraft     client = Minecraft.getInstance();

        // FRAME_BLENDING mode: runs on both the post-render and F5/entity-ride passes
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.FRAME_BLENDING) {
            if (pass != BlurPass.NORMAL_POST && pass != BlurPass.SPECIAL_F5) return;

            float fps         = frameTimer.getFPS();
            int   refreshRate = frameTimer.getRefreshRate();

            FrameAccumulationManager.applyAccumulationBlur(frameAllocator, fps, refreshRate);
            return;
        }

        // VELOCITY_BASED mode: velocity-based sampling (pre-entity + post-render passes)
        BlurStrengthCalculator.Result blur = strengthCalc.calculate(
                config.motionBlurStrength,
                frameTimer.getFPS(),
                frameTimer.getRefreshRate(),
                config.refreshRateScaling
        );
        float viewW = client.getMainRenderTarget().width;
        float viewH = client.getMainRenderTarget().height;
        int   algo  = config.blurAlgorithm.ordinal();

        switch (pass) {
            case NORMAL_PRE -> {
                PostChain p = getPreProcessor(client);
                if (p != null) writeAndRun(p, "PreEntityBlurUniforms",  true,  blur.strength, viewW, viewH, algo, blur.sampleAmount, client);
            }
            case SPECIAL_F5 -> {
                PostChain p = getF5Processor(client);
                if (p != null) writeAndRun(p, "PreEntityBlurUniforms",  true,  blur.strength, viewW, viewH, algo, blur.sampleAmount, client);
            }
            case NORMAL_POST -> {
                PostChain p = getPostProcessor(client);
                if (p != null) writeAndRun(p, "PostRenderBlurUniforms", false, blur.strength, viewW, viewH, algo, blur.sampleAmount, client);
            }
        }
    }

    // Shader cache

    private static PostChain getPreProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "motion_blur_pre", "pre-entity", preLoadErrorLogged);
        if (result == null) { cachedPreProcessor = null; return null; }
        if (result != cachedPreProcessor) { cachedPreProcessor = result; preEntityUBO = null; }
        preLoadErrorLogged = false;
        return cachedPreProcessor;
    }

    private static PostChain getF5Processor(Minecraft client) {
        PostChain result = loadProcessor(client, "motion_blur_f5", "F5/entity-riding", f5LoadErrorLogged);
        if (result == null) { cachedF5Processor = null; return null; }
        if (result != cachedF5Processor) { cachedF5Processor = result; preEntityUBO = null; }
        f5LoadErrorLogged = false;
        return cachedF5Processor;
    }

    private static PostChain getPostProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "motion_blur_post", "post-render", postLoadErrorLogged);
        if (result == null) { cachedPostProcessor = null; return null; }
        if (result != cachedPostProcessor) { cachedPostProcessor = result; postRenderUBO = null; }
        postLoadErrorLogged = false;
        return cachedPostProcessor;
    }

    private static PostChain loadProcessor(Minecraft client, String shaderName,
                                           String displayName, boolean alreadyLogged) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            return cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
        } catch (Exception e) {
            if (!alreadyLogged) {
                System.err.println("[NaturalMotionBlur] Failed to load " + displayName + " shader: " + e.getMessage());
                switch (shaderName) {
                    case "motion_blur_pre"  -> preLoadErrorLogged  = true;
                    case "motion_blur_f5"   -> f5LoadErrorLogged   = true;
                    case "motion_blur_post" -> postLoadErrorLogged = true;
                }
            }
            return null;
        }
    }

    // UBO writing

    private static void writeAndRun(PostChain processor, String uboKey, boolean isPreSlot,
                                    float blendFactor, float viewW, float viewH,
                                    int blurAlgorithm, int sampleAmount, Minecraft client) {
        List<PostPass> passes = ((PostChainAccessor) processor).getPasses();
        if (passes.isEmpty()) return;

        Map<String, GpuBuffer> uniformBuffers = ((PostPassAccessor) passes.getFirst()).getCustomUniforms();
        if (!uniformBuffers.containsKey(uboKey)) return;

        if ( isPreSlot && preEntityUBO  == null) preEntityUBO  = createBufferCompat();
        if (!isPreSlot && postRenderUBO == null) postRenderUBO = createBufferCompat();
        GpuBuffer ubo = isPreSlot ? preEntityUBO : postRenderUBO;

        // Replace the shader loader's placeholder buffer with ours and close the old one
        GpuBuffer old = uniformBuffers.put(uboKey, ubo);
        if (old != null && old != ubo) old.close();

        // Write uniforms in std140 order — must match the GLSL block declaration exactly
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putMat4f(cameraState.getMvInverse());
            b.putMat4f(cameraState.getProjInverse());
            b.putMat4f(cameraState.getPrevModelView());
            b.putMat4f(cameraState.getPrevProjection());
            b.putVec3(cameraState.getDx(), cameraState.getDy(), cameraState.getDz());
            b.putVec2(viewW, viewH);
            b.putFloat(blendFactor);
            b.putInt(sampleAmount);
            b.putInt(blurAlgorithm);
            b.putInt(1); // padding
        }

        processor.process(client.getMainRenderTarget(), frameAllocator);
    }

    // Use reflection
    private static GpuBuffer createBufferCompat() {
        Object device = RenderSystem.getDevice();
        java.util.function.Supplier<String> name = () -> "naturalmotionblur:MotionBlurUniforms";
        try {
            if (createBufferMethod == null)
                createBufferMethod = device.getClass().getMethod("createBuffer",
                        java.util.function.Supplier.class, int.class, long.class);
            return (GpuBuffer) createBufferMethod.invoke(device, name, 130, (long) UBO_SIZE);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[NMB] createBuffer failed", e);
        }
        throw new RuntimeException("[NMB] No compatible createBuffer found on " + device.getClass());
    }
}