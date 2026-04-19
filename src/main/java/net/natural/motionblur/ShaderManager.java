package net.natural.motionblur;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
import net.natural.motionblur.shader.BlurStrengthCalculator;
import net.natural.motionblur.shader.CameraState;
import net.natural.motionblur.shader.FrameBlendingManager;
import net.natural.motionblur.shader.FrameTimer;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class ShaderManager {

    private static final FrameTimer             frameTimer   = new FrameTimer();
    private static final CameraState            cameraState  = new CameraState();
    private static final BlurStrengthCalculator strengthCalc = new BlurStrengthCalculator();

    private static GraphicsResourceAllocator frameAllocator = null;

    private static PostChain cachedPreProcessor  = null;
    private static PostChain cachedF5Processor   = null;
    private static PostChain cachedPostProcessor = null;
    private static final Set<String> loadErrorLogged = new HashSet<>();

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

        // Accumulation / Frame Blending — handled separately
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.FRAME_BLENDING) {
            FrameBlendingManager.applyFrameBlending(
                    frameAllocator, frameTimer.getFPS(), frameTimer.getRefreshRate());
            return;
        }
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.ACCUMULATION_MAX) {
            FrameBlendingManager.applyAccumulationMax(frameAllocator, config.motionBlurStrength);
            return;
        }
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.ACCUMULATION_MIX) {
            FrameBlendingManager.applyAccumulationMix(frameAllocator, config.motionBlurStrength);
            return;
        }

        // Velocity blur
        BlurStrengthCalculator.Result blur = strengthCalc.calculate(
                config.motionBlurStrength,
                frameTimer.getFPS(),
                frameTimer.getRefreshRate(),
                config.refreshRateScaling);
        float viewW = client.getMainRenderTarget().width;
        float viewH = client.getMainRenderTarget().height;

        // Capture uniform values before lambda
        float[] mvInv   = mat4ToArray(cameraState.getMvInverse());
        float[] projInv = mat4ToArray(cameraState.getProjInverse());
        float[] prevMV  = mat4ToArray(cameraState.getPrevModelView());
        float[] prevP   = mat4ToArray(cameraState.getPrevProjection());
        float dx = cameraState.getDx(), dy = cameraState.getDy(), dz = cameraState.getDz();
        float strength = blur.strength();
        int sampleAmount = blur.sampleAmount();

        Consumer<RenderPass> uniformSetter = (RenderPass rp) -> {
            trySetUniform(rp, "mvInverse",         mvInv);
            trySetUniform(rp, "projInverse",       projInv);
            trySetUniform(rp, "prevModelView",     prevMV);
            trySetUniform(rp, "prevProjection",    prevP);
            trySetUniform(rp, "cameraDelta",       new float[]{dx, dy, dz});
            trySetUniform(rp, "view_res",          new float[]{viewW, viewH});
            trySetUniform(rp, "blendFactor",       new float[]{strength});
            trySetUniform(rp, "BlendFactor",       new float[]{strength});
            trySetUniform(rp, "sampleCount",       new int[]{sampleAmount});
            trySetUniform(rp, "motionBlurSamples", new int[]{sampleAmount});
            trySetUniform(rp, "blurAlgorithm",     new int[]{config.blurAlgorithm.ordinal()});
            trySetUniform(rp, "useDepth",          new int[]{1});
        };

        switch (pass) {
            case NORMAL_PRE -> {
                PostChain p = getPreProcessor(client);
                if (p != null) p.process(client.getMainRenderTarget(), frameAllocator, uniformSetter);
            }
            case SPECIAL_F5 -> {
                PostChain p = getF5Processor(client);
                if (p != null) p.process(client.getMainRenderTarget(), frameAllocator, uniformSetter);
            }
            case NORMAL_POST -> {
                PostChain p = getPostProcessor(client);
                if (p != null) p.process(client.getMainRenderTarget(), frameAllocator, uniformSetter);
            }
        }
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

    private static PostChain loadProcessor(Minecraft client, String shaderName, String displayName) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain chain = cache.getOrLoadPostChain(
                    ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged.remove(shaderName);
            return chain;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + displayName + " shader: " + e.getMessage());
            return null;
        }
    }

    // Uniform helpers

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
}