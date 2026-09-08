package net.natural.motionblur;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
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
import net.natural.motionblur.util.ClientRenderTargets;
import net.natural.motionblur.util.GpuBufferUtil;
import net.natural.motionblur.util.IrisCompat;
import net.natural.motionblur.util.ManagedUniformBuffer;
import org.joml.Matrix4f;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShaderManager {

    private static final FrameTimer             frameTimer   = new FrameTimer();
    private static final CameraState            cameraState  = new CameraState();
    private static final BlurStrengthCalculator strengthCalc = new BlurStrengthCalculator();

    private static GraphicsResourceAllocator frameAllocator = null;
    private static boolean deferredTemporalBlurApplied = false;

    private static PostChain cachedPreProcessor             = null;
    private static PostChain cachedF5Processor              = null;
    private static PostChain cachedPostProcessor            = null;
    private static PostChain cachedIrisDeferredPreProcessor = null;
    private static final Set<String> loadErrorLogged = new HashSet<>();

    private static final Identifier IRIS_PRE_DEPTH_TARGET_ID =
            Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "pre_depth");
    private static final Set<Identifier> IRIS_DEFERRED_PRE_TARGETS =
            Set.of(PostChain.MAIN_TARGET_ID, IRIS_PRE_DEPTH_TARGET_ID);

    private static TextureTarget irisPreDepthTarget = null;
    private static boolean irisPreDepthCaptured = false;
    private static boolean irisDepthCaptureErrorLogged = false;
    private static boolean irisDeferredApplyErrorLogged = false;

    private static final int UBO_SIZE = 304;
    private static final ManagedUniformBuffer preEntityUBO       = new ManagedUniformBuffer("PreEntityBlurUniforms",  UBO_SIZE);
    private static final ManagedUniformBuffer f5EntityUBO        = new ManagedUniformBuffer("PreEntityBlurUniforms",  UBO_SIZE);
    private static final ManagedUniformBuffer irisDeferredPreUBO = new ManagedUniformBuffer("PreEntityBlurUniforms",  UBO_SIZE);
    private static final ManagedUniformBuffer postRenderUBO      = new ManagedUniformBuffer("PostRenderBlurUniforms", UBO_SIZE);

    public static void captureAllocator(GraphicsResourceAllocator allocator) { frameAllocator = allocator; }
    public static void clearFrameAllocator() { frameAllocator = null; }
    public static void beginFrame() {
        frameTimer.beginFrame();
        deferredTemporalBlurApplied = false;
        irisPreDepthCaptured = false;
    }
    public static float getCurrentFPS() { return frameTimer.getFPS(); }
    public static void invalidate() {
        preEntityUBO.reset();
        f5EntityUBO.reset();
        irisDeferredPreUBO.reset();
        postRenderUBO.reset();
        if (irisPreDepthTarget != null) {
            irisPreDepthTarget.destroyBuffers();
            irisPreDepthTarget = null;
        }
        irisPreDepthCaptured = false;
        FrameBlendingManager.invalidate();
    }

    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView,
                                          Matrix4f projection, Matrix4f prevProjection,
                                          float dx, float dy, float dz) {
        cameraState.setFrame(modelView, prevModelView, projection, prevProjection, dx, dy, dz);
    }

    public static void applyPostRenderVelocityOnly() { if (shouldRun()) applyPostRenderVelocityOnlyInternal(); }
    public static void applyPreEntityVelocityOnly(boolean specialSingleBlur) { if (shouldRun()) applyPreEntityVelocityOnlyInternal(specialSingleBlur); }
    public static void applyDeferredIrisPreEntityVelocityOnly(boolean specialSingleBlur) {
        if (shouldRun()) applyDeferredIrisPreEntityVelocityOnlyInternal(specialSingleBlur);
    }

    public static void applyDeferredTemporalBlur() {
        if (deferredTemporalBlurApplied || frameAllocator == null || !shouldRun()) return;

        ConfigEntries config = ConfigManager.getConfig();
        switch (config.blurAlgorithm) {
            case FRAME_BLENDING, HYBRID_BLENDING -> {
                applyFrameBlendingInternal();
                deferredTemporalBlurApplied = true;
            }
            case ACCUMULATION_MAX -> {
                FrameBlendingManager.applyAccumulationMax(
                        frameAllocator, config.getEffectiveMotionBlurStrength());
                deferredTemporalBlurApplied = true;
            }
            case ACCUMULATION_MIX -> {
                FrameBlendingManager.applyAccumulationMix(
                        frameAllocator, config.getEffectiveMotionBlurStrength());
                deferredTemporalBlurApplied = true;
            }
            default -> {}
        }
    }

    private static boolean shouldRun() {
        ConfigEntries config = ConfigManager.getConfig();
        return config.enabled && config.getEffectiveMotionBlurStrength() != 0;
    }

    private static void applyPreEntityVelocityOnlyInternal(boolean specialSingleBlur) {
        if (frameAllocator == null) return;

        ConfigEntries config = ConfigManager.getConfig();
        if (!config.usesVelocityBlur()) return;

        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);

        if (IrisCompat.isShaderPackInUse()) {
            captureIrisPreEntityDepth(main);
            return;
        }

        BlurStrengthCalculator.Result blur = calculateVelocityBlur(config);

        float viewW = main.width;
        float viewH = main.height;
        int algo = config.blurAlgorithm.ordinal();

        PostChain processor = specialSingleBlur ? getF5Processor(client) : getPreProcessor(client);
        ManagedUniformBuffer ubo = specialSingleBlur ? f5EntityUBO : preEntityUBO;
        if (processor != null && writeUniforms(processor, "PreEntityBlurUniforms", ubo,
                blur.strength(), viewW, viewH, algo, blur.sampleAmount())) {
            try {
                processor.process(main, frameAllocator);
            } catch (RuntimeException e) {
                if (ubo.resetIfClosed(e)) return;
                throw e;
            }
        }
    }

    private static void captureIrisPreEntityDepth(RenderTarget main) {
        if (main.getDepthTexture() == null) {
            irisPreDepthCaptured = false;
            return;
        }

        try {
            if (irisPreDepthTarget == null
                    || irisPreDepthTarget.width != main.width
                    || irisPreDepthTarget.height != main.height) {
                if (irisPreDepthTarget != null) irisPreDepthTarget.destroyBuffers();
                irisPreDepthTarget = new TextureTarget(
                        "NaturalMotionBlur / Iris pre-entity depth",
                        main.width, main.height, true, GpuFormat.RGBA8_UNORM);
            }

            irisPreDepthTarget.copyDepthFrom(main);
            irisPreDepthCaptured = true;
            irisDepthCaptureErrorLogged = false;
        } catch (RuntimeException e) {
            irisPreDepthCaptured = false;
            if (!irisDepthCaptureErrorLogged) {
                irisDepthCaptureErrorLogged = true;
                System.err.println("[NaturalMotionBlur] Could not capture Iris pre-entity depth: " + e);
            }
        }
    }

    private static void applyDeferredIrisPreEntityVelocityOnlyInternal(boolean specialSingleBlur) {
        if (frameAllocator == null || !irisPreDepthCaptured || irisPreDepthTarget == null) return;
        if (!IrisCompat.isShaderPackInUse()) return;

        ConfigEntries config = ConfigManager.getConfig();
        if (!config.usesVelocityBlur()) return;

        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);
        if (main.width != irisPreDepthTarget.width || main.height != irisPreDepthTarget.height) {
            irisPreDepthCaptured = false;
            return;
        }

        BlurStrengthCalculator.Result blur = calculateVelocityBlur(config);

        PostChain processor = getIrisDeferredPreProcessor(client);
        if (processor == null) {
            irisPreDepthCaptured = false;
            return;
        }

        int mode = specialSingleBlur ? 2 : 1;
        if (writeUniforms(processor, "PreEntityBlurUniforms", irisDeferredPreUBO,
                blur.strength(), main.width, main.height, config.blurAlgorithm.ordinal(), blur.sampleAmount(), mode)) {
            try {
                runWithIrisPreDepth(processor, main, irisPreDepthTarget);
                irisDeferredApplyErrorLogged = false;
            } catch (RuntimeException e) {
                if (irisDeferredPreUBO.resetIfClosed(e)) {
                    irisPreDepthCaptured = false;
                    return;
                }
                if (!irisDeferredApplyErrorLogged) {
                    irisDeferredApplyErrorLogged = true;
                    System.err.println("[NaturalMotionBlur] Iris deferred pre-entity blur failed: " + e);
                }
            }
        }

        irisPreDepthCaptured = false;
    }

    private static void runWithIrisPreDepth(PostChain processor, RenderTarget main, RenderTarget preDepth) {
        FrameGraphBuilder frame = new FrameGraphBuilder();
        Map<Identifier, ResourceHandle<RenderTarget>> targets = new java.util.HashMap<>();
        targets.put(PostChain.MAIN_TARGET_ID, frame.importExternal("main", main));
        targets.put(IRIS_PRE_DEPTH_TARGET_ID, frame.importExternal("naturalmotionblur:pre_depth", preDepth));

        PostChain.TargetBundle bundle = new PostChain.TargetBundle() {
            @Override
            public void replace(@NonNull Identifier id, @NonNull ResourceHandle<RenderTarget> handle) {
                if (!targets.containsKey(id)) {
                    throw new IllegalArgumentException("No target with id " + id);
                }
                targets.put(id, handle);
            }

            @Override
            public ResourceHandle<RenderTarget> get(@NonNull Identifier id) {
                return targets.get(id);
            }
        };

        processor.addToFrame(frame, main.width, main.height, bundle);
        frame.execute(frameAllocator);
    }

    private static void applyPostRenderVelocityOnlyInternal() {
        if (frameAllocator == null) return;

        ConfigEntries config = ConfigManager.getConfig();
        if (!config.usesVelocityBlur()) {return;}

        Minecraft client = Minecraft.getInstance();
        BlurStrengthCalculator.Result blur = calculateVelocityBlur(config);
        RenderTarget main = ClientRenderTargets.getMain(client);
        float viewW = main.width;
        float viewH = main.height;
        int   algo  = config.blurAlgorithm.ordinal();

        PostChain p = getPostProcessor(client);
        if (p != null) {
            writeAndRun(p, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
        }
    }

    private static void applyFrameBlendingInternal() {
        if (frameAllocator == null) return;
        ConfigEntries config = ConfigManager.getConfig();
        FrameBlendingManager.applyFrameBlending(
                frameAllocator,
                frameTimer.getFPS(),
                frameTimer.getRefreshRate(),
                config.getEffectiveMotionBlurStrength());
    }

    private static BlurStrengthCalculator.Result calculateVelocityBlur(ConfigEntries config) {
        float fps = frameTimer.getFPS();
        int refreshRate = frameTimer.getRefreshRate();

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
            float fillerStrength = FrameBlendingManager.getHybridVelocityStrength(
                    fps, refreshRate, config.getEffectiveMotionBlurStrength());
            int sampleAmount = Math.max(100, Math.round(100.0f * fillerStrength));
            return new BlurStrengthCalculator.Result(fillerStrength, sampleAmount);
        }

        return strengthCalc.calculate(
                config.getEffectiveMotionBlurStrength(),
                fps,
                refreshRate,
                config.refreshRateScaling && config.allowsRefreshRateScaling());
    }

    // Shader cache

    private static PostChain getPreProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_pre", "pre-entity");
        if (result == null) { cachedPreProcessor = null; return null; }
        if (result != cachedPreProcessor) cachedPreProcessor = result;
        return cachedPreProcessor;
    }

    private static PostChain getF5Processor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_f5", "F5/entity-riding");
        if (result == null) { cachedF5Processor = null; return null; }
        if (result != cachedF5Processor) cachedF5Processor = result;
        return cachedF5Processor;
    }

    private static PostChain getPostProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_post", "post-render");
        if (result == null) { cachedPostProcessor = null; return null; }
        if (result != cachedPostProcessor) cachedPostProcessor = result;
        return cachedPostProcessor;
    }

    private static PostChain getIrisDeferredPreProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_pre_iris_deferred",
                "Iris deferred pre-entity", IRIS_DEFERRED_PRE_TARGETS);
        if (result == null) { cachedIrisDeferredPreProcessor = null; return null; }
        if (result != cachedIrisDeferredPreProcessor) cachedIrisDeferredPreProcessor = result;
        return cachedIrisDeferredPreProcessor;
    }

    private static PostChain loadProcessor(Minecraft client, String shaderName, String displayName) {
        return loadProcessor(client, shaderName, displayName, LevelTargetBundle.MAIN_TARGETS);
    }

    private static PostChain loadProcessor(Minecraft client, String shaderName, String displayName, Set<Identifier> allowedTargets) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain chain = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    allowedTargets);
            loadErrorLogged.remove(shaderName);
            return chain;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + displayName + " shader: " + e.getMessage());
            return null;
        }
    }

    // UBO writing

    private static void writeAndRun(PostChain processor, float blendFactor, float viewW, float viewH, int blurAlgorithm, int sampleAmount, Minecraft client) {
        if (writeUniforms(processor, "PostRenderBlurUniforms", ShaderManager.postRenderUBO, blendFactor, viewW, viewH, blurAlgorithm, sampleAmount)) {
            try {
                processor.process(ClientRenderTargets.getMain(client), frameAllocator);
            } catch (RuntimeException e) {
                if (ShaderManager.postRenderUBO.resetIfClosed(e)) return;
                throw e;
            }
        }
    }

    private static boolean writeUniforms(PostChain processor, String uboKey, ManagedUniformBuffer managedUBO, float blendFactor, float viewW, float viewH, int blurAlgorithm, int sampleAmount) {
        return writeUniforms(processor, uboKey, managedUBO, blendFactor, viewW, viewH, blurAlgorithm, sampleAmount, 1);
    }

    private static boolean writeUniforms(PostChain processor, String uboKey, ManagedUniformBuffer managedUBO, float blendFactor, float viewW, float viewH, int blurAlgorithm, int sampleAmount, int useDepthMode) {
        List<PostPass> passes = ((PostChainAccessor) processor).getPasses();
        if (passes.isEmpty()) return false;

        Map<String, GpuBuffer> uniformBuffers = ((PostPassAccessor) passes.getFirst()).getCustomUniforms();
        if (!uniformBuffers.containsKey(uboKey)) return false;

        GpuBuffer ubo = managedUBO.put(processor, uniformBuffers, uboKey);

        try {
            GpuBufferUtil.writeStd140(ubo, UBO_SIZE, b -> {
                b.putMat4f(cameraState.getMvInverse());
                b.putMat4f(cameraState.getProjInverse());
                b.putMat4f(cameraState.getPrevModelView());
                b.putMat4f(cameraState.getPrevProjection());
                b.putVec3(cameraState.getDx(), cameraState.getDy(), cameraState.getDz());
                b.putVec2(viewW, viewH);
                b.putFloat(blendFactor);
                b.putInt(sampleAmount);
                b.putInt(blurAlgorithm);
                b.putInt(useDepthMode);
                b.putInt(IrisCompat.getDepthConvention());
            });
            return true;
        } catch (RuntimeException e) {
            if (managedUBO.resetIfClosed(e)) return false;
            throw e;
        }
    }
}