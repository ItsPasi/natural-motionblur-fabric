package net.natural.motionblur.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import net.natural.motionblur.NaturalMotionBlurMod;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class FrameAccumulationManager {

    // UBO layout: 1 float + 3 int padding = 16 bytes (std140)
    private static final int UBO_SIZE = 16;

    private static final String ACCUMULATION_SAMPLER = "Accumulation";
    private static final String DISPLAY_SAMPLER      = "Display";

    // Chain 1 — accumulation
    private static PostChain cachedAccumChain     = null;
    private static boolean   accumLoadErrorLogged = false;
    private static GpuBuffer accumulationUBO      = null;

    // Chain 2 — display lerp
    private static PostChain cachedLerpChain     = null;
    private static boolean   lerpLoadErrorLogged = false;
    private static GpuBuffer displayLerpUBO      = null;

    // Persistent GPU targets
    private static RenderTarget accumulationTarget = null; // working box-filter average (never shown directly)
    private static RenderTarget displayTarget      = null; // last completed N-frame average
    private static int          targetW            = 0;
    private static int          targetH            = 0;
    private static boolean      displayInitialized = false; // true once displayTarget has valid content

    // Injected inputs that bypass FrameGraphBuilder
    private static PersistentTextureInput injectedAccumInput   = null;
    private static PersistentTextureInput injectedDisplayInput = null;

    private static Method createBufferMethod = null;

    // Interval tracking.
    private static int   frameIndexInInterval = 0;
    private static int   lockedN              = 1;
    private static float smoothedFPS          = 0;

    public static void applyAccumulationBlur(GraphicsResourceAllocator allocator, float fps, int refreshRate) {
        // Smooth FPS with a moderate EMA to dampen per-frame spike noise while still tracking genuine framerate changes within a few hundred milliseconds.
        if (fps > 0) {
            smoothedFPS = (smoothedFPS <= 0) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }

        // Only recalculate N at interval boundaries
        if (frameIndexInInterval == 0) {
            lockedN = (smoothedFPS > 0 && refreshRate > 0)
                    ? Math.max(1, Math.round(smoothedFPS / refreshRate))
                    : 1;
        }

        int   k             = frameIndexInInterval + 1; // 1-indexed
        float blendWeight   = 1.0f / k;                 // chain 1: running box-filter weight
        float displayWeight = (float) k / lockedN;      // chain 2: lerp toward current avg (0→1)

        Minecraft    client = Minecraft.getInstance();
        RenderTarget main   = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);

        // Accumulation
        PostChain accumChain = getChain(client, "motion_blur_accumulation", "accumulation",
                cachedAccumChain, accumLoadErrorLogged);
        if (accumChain == null) return;
        cachedAccumChain = accumChain;

        PostPass accumPass = firstPass(accumChain);
        if (accumPass == null) return;

        Map<String, GpuBuffer> accumUniforms = ((PostPassAccessor) accumPass).getCustomUniforms();
        if (!accumUniforms.containsKey("FrameAccumulationUniforms")) return;

        if (accumulationUBO == null) accumulationUBO = createBufferCompat("FrameAccumulationUniforms");
        replaceUBO(accumUniforms, "FrameAccumulationUniforms", accumulationUBO);
        writeFloatUBO(accumulationUBO, blendWeight);

        // Inject AccumulationSampler → accumulationTarget (persistent running average)
        if (injectedAccumInput == null)
            injectedAccumInput = new PersistentTextureInput(ACCUMULATION_SAMPLER, accumulationTarget);
        injectSampler(accumPass, ACCUMULATION_SAMPLER, injectedAccumInput);

        accumChain.process(main, allocator);

        // Save running average: main → accumulationTarget (for next frame's blend input)
        copyTexture(main, accumulationTarget);

        // When the interval is complete, promote the running average to displayTarget.
        // displayTarget holds the last complete N-frame box-filter average used by chain 2.
        if (k >= lockedN) {
            copyTexture(accumulationTarget, displayTarget);
            displayInitialized = true;
        }

        // Advance frame counter — reset to 0 after lockedN frames
        frameIndexInInterval = (k >= lockedN) ? 0 : k;

        // Chain 2: Display lerp
        if (!displayInitialized) return;

        PostChain lerpChain = getChain(client, "motion_blur_display_lerp", "display-lerp",
                cachedLerpChain, lerpLoadErrorLogged);
        if (lerpChain == null) return;
        cachedLerpChain = lerpChain;

        PostPass lerpPass = firstPass(lerpChain);
        if (lerpPass == null) return;

        Map<String, GpuBuffer> lerpUniforms = ((PostPassAccessor) lerpPass).getCustomUniforms();
        if (!lerpUniforms.containsKey("DisplayLerpUniforms")) return;

        if (displayLerpUBO == null) displayLerpUBO = createBufferCompat("DisplayLerpUniforms");
        replaceUBO(lerpUniforms, "DisplayLerpUniforms", displayLerpUBO);
        writeFloatUBO(displayLerpUBO, displayWeight);

        // Inject DisplaySampler → displayTarget (persistent completed average)
        if (injectedDisplayInput == null)
            injectedDisplayInput = new PersistentTextureInput(DISPLAY_SAMPLER, displayTarget);
        injectSampler(lerpPass, DISPLAY_SAMPLER, injectedDisplayInput);

        // main currently holds the running average (from chain 1). Chain 2 reads it as
        // MainSampler and lerps it with DisplaySampler → outputs blended result to main.
        lerpChain.process(main, allocator);
    }

    // Called on window resize — destroy everything so it rebuilds at the new resolution
    public static void invalidate() {
        destroyTarget(accumulationTarget); accumulationTarget = null;
        destroyTarget(displayTarget);      displayTarget      = null;
        targetW            = 0;
        targetH            = 0;
        injectedAccumInput   = null;
        injectedDisplayInput = null;
        cachedAccumChain     = null;
        cachedLerpChain      = null;
        accumulationUBO      = null;
        displayLerpUBO       = null;
        accumLoadErrorLogged = false;
        lerpLoadErrorLogged  = false;
        frameIndexInInterval = 0;
        lockedN              = 1;
        smoothedFPS          = 0;
        displayInitialized   = false;
    }

    // Private helpers

    private static void ensureTargets(int w, int h) {
        if (accumulationTarget != null && targetW == w && targetH == h) return;
        destroyTarget(accumulationTarget);
        destroyTarget(displayTarget);
        accumulationTarget   = new MainTarget(w, h);
        displayTarget        = new MainTarget(w, h);
        targetW              = w;
        targetH              = h;
        injectedAccumInput   = null; // texture view changed — force re-wrap
        injectedDisplayInput = null;
        displayInitialized   = false;
    }

    private static void destroyTarget(RenderTarget t) {
        if (t != null) t.destroyBuffers();
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, dst.width, dst.height);
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void injectSampler(PostPass pass, String samplerName, PersistentTextureInput input) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (samplerName.equals(inputs.get(i).samplerName())) {
                inputs.set(i, input);
                return;
            }
        }
    }

    private static void replaceUBO(Map<String, GpuBuffer> map, String key, GpuBuffer ubo) {
        GpuBuffer old = map.put(key, ubo);
        if (old != null && old != ubo) old.close();
    }

    private static void writeFloatUBO(GpuBuffer ubo, float value) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putFloat(value);
            b.putInt(0); // padding0
            b.putInt(0); // padding1
            b.putInt(0); // padding2
        }
    }

    // Shader/chain cache

    private static PostChain getChain(Minecraft client, String shaderName, String displayName,
                                       PostChain cached, boolean alreadyLogged) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;

            PostChain result = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);

            if (result != cached) {
                // Chain reloaded
                if ("motion_blur_accumulation".equals(shaderName)) {
                    accumulationUBO    = null;
                    injectedAccumInput = null;
                } else {
                    displayLerpUBO       = null;
                    injectedDisplayInput = null;
                }
            }
            return result;

        } catch (Exception e) {
            if (!alreadyLogged) {
                System.err.println("[NaturalMotionBlur] Failed to load " + displayName + " shader: " + e.getMessage());
                if ("motion_blur_accumulation".equals(shaderName)) accumLoadErrorLogged = true;
                else lerpLoadErrorLogged = true;
            }
            return null;
        }
    }

    // PersistentTextureInput
    private static class PersistentTextureInput implements PostPass.Input {
        private final String       samplerName;
        private final RenderTarget target;

        PersistentTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target      = target;
        }

        @Override
        public void addToPass(FramePass pass, Map<Identifier, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public GpuTextureView texture(Map<Identifier, ResourceHandle<RenderTarget>> targets) {
            return target.getColorTextureView();
        }

        @Override public String  samplerName() { return samplerName; }
        @Override public boolean bilinear()    { return false; }
    }

    // Buffer creation

    private static GpuBuffer createBufferCompat(String debugName) {
        Object device = RenderSystem.getDevice();
        java.util.function.Supplier<String> name = () -> "naturalmotionblur:" + debugName;
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
