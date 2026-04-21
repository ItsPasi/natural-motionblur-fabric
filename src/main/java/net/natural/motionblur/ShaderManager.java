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
import net.natural.motionblur.shader.FrameBlendingManager;
import net.natural.motionblur.shader.FrameTimer;
import net.natural.motionblur.util.GpuBufferUtil;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShaderManager {

    private static final FrameTimer             frameTimer   = new FrameTimer();
    private static final CameraState            cameraState  = new CameraState();
    private static final BlurStrengthCalculator strengthCalc = new BlurStrengthCalculator();

    private static GraphicsResourceAllocator frameAllocator = null;

    private static PostChain cachedPreProcessor  = null;
    private static PostChain cachedF5Processor   = null;
    private static PostChain cachedPostProcessor = null;
    private static final Set<String> loadErrorLogged = new HashSet<>();

    private static final int UBO_SIZE = 304;
    private static GpuBuffer preEntityUBO  = null;
    private static GpuBuffer postRenderUBO = null;

    private enum BlurPass { NORMAL_PRE, SPECIAL_F5, NORMAL_POST }

    public static void captureAllocator(GraphicsResourceAllocator allocator) { frameAllocator = allocator; }
    public static void clearFrameAllocator() { frameAllocator = null; }
    public static void beginFrame() { frameTimer.beginFrame(); }
    public static float getCurrentFPS() { return frameTimer.getFPS(); }
    public static void invalidate() { FrameBlendingManager.invalidate(); }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          float dx, float dy, float dz) {
        cameraState.setFrame(modelView, prevModelView, projection, prevProjection, dx, dy, dz);
    }

    public static void applyPreEntityBlur()     { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_PRE);  }
    public static void applyF5EntityRideBlur()  { if (shouldRun()) applyBlurInternal(BlurPass.SPECIAL_F5);  }
    public static void applyPostRenderBlur()    { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_POST); }
    public static void applyFrameBlendingOnly() { if (shouldRun()) applyFrameBlendingInternal(); }

    private static boolean shouldRun() {
        ConfigEntries config = ConfigManager.getConfig();
        return config.enabled && config.getEffectiveMotionBlurStrength() != 0;
    }

    private static void applyBlurInternal(BlurPass pass) {
        if (frameAllocator == null) return;

        ConfigEntries config = ConfigManager.getConfig();
        Minecraft     client = Minecraft.getInstance();

        // Accumulation Options
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.FRAME_BLENDING) {
            if (pass == BlurPass.NORMAL_POST) {
                FrameBlendingManager.applyFrameBlending(
                        frameAllocator, frameTimer.getFPS(), frameTimer.getRefreshRate());
            }
            return;
        }

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.ACCUMULATION_MAX) {
            FrameBlendingManager.applyAccumulationMax(frameAllocator, config.getEffectiveMotionBlurStrength());
            return;
        }

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.ACCUMULATION_MIX) {
            FrameBlendingManager.applyAccumulationMix(frameAllocator, config.getEffectiveMotionBlurStrength());
            return;
        }

        // Velocity Option
        BlurStrengthCalculator.Result blur = strengthCalc.calculate(
                config.getEffectiveMotionBlurStrength(),
                frameTimer.getFPS(),
                frameTimer.getRefreshRate(),
                config.refreshRateScaling && config.allowsRefreshRateScaling());
        float viewW = client.getMainRenderTarget().width;
        float viewH = client.getMainRenderTarget().height;
        int   algo  = config.blurAlgorithm.ordinal();

        switch (pass) {
            case NORMAL_PRE -> {
                PostChain p = getPreProcessor(client);
                if (p != null) {
                    writeAndRun(p, "PreEntityBlurUniforms", true, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
            }
            case SPECIAL_F5 -> {
                PostChain p = getF5Processor(client);
                if (p != null) {
                    writeAndRun(p, "PreEntityBlurUniforms", true, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
            }
            case NORMAL_POST -> {
                PostChain p = getPostProcessor(client);
                if (p != null) {
                    writeAndRun(p, "PostRenderBlurUniforms", false, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
                if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
                    applyFrameBlendingInternal();
                }
            }
        }
    }

    private static void applyFrameBlendingInternal() {
        if (frameAllocator == null) return;
        FrameBlendingManager.applyFrameBlending(
                frameAllocator, frameTimer.getFPS(), frameTimer.getRefreshRate());
    }

    // Shader cache

    private static PostChain getPreProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_pre", "pre-entity");
        if (result == null) { cachedPreProcessor = null; return null; }
        if (result != cachedPreProcessor) { cachedPreProcessor = result; preEntityUBO = null; }
        return cachedPreProcessor;
    }

    private static PostChain getF5Processor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_f5", "F5/entity-riding");
        if (result == null) { cachedF5Processor = null; return null; }
        if (result != cachedF5Processor) { cachedF5Processor = result; preEntityUBO = null; }
        return cachedF5Processor;
    }

    private static PostChain getPostProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_post", "post-render");
        if (result == null) { cachedPostProcessor = null; return null; }
        if (result != cachedPostProcessor) { cachedPostProcessor = result; postRenderUBO = null; }
        return cachedPostProcessor;
    }

    private static PostChain loadProcessor(Minecraft client, String shaderName, String displayName) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain chain = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged.remove(shaderName);
            return chain;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + displayName + " shader: " + e.getMessage());
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

        if ( isPreSlot && preEntityUBO  == null) preEntityUBO  = GpuBufferUtil.createUBO("PreEntityBlurUniforms",  UBO_SIZE);
        if (!isPreSlot && postRenderUBO == null) postRenderUBO = GpuBufferUtil.createUBO("PostRenderBlurUniforms", UBO_SIZE);
        GpuBuffer ubo = isPreSlot ? preEntityUBO : postRenderUBO;

        // Replace the shader loader's placeholder buffer with ours and close the old one
        GpuBuffer old = uniformBuffers.put(uboKey, ubo);
        if (old != null && old != ubo) old.close();

        // Write uniforms in std140 order - must match the GLSL block declaration
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
            b.putInt(1);
        }

        processor.process(client.getMainRenderTarget(), frameAllocator);
    }
}