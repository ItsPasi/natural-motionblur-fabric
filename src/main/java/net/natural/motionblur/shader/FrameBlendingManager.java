package net.natural.motionblur.shader;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.natural.motionblur.NaturalMotionBlurMod;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrameBlendingManager {


    private static final int MAX_HISTORY = 8;
    private static final int WINDOW_CHANGE_HOLD_FRAMES = 6;

    // Frame blending history
    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled     = 0;

    private static int   lockedN        = 1;
    private static int   pendingN       = 1;
    private static int   pendingFrames  = 0;
    private static float smoothedFPS    = 0;

    // Accumulation
    private static RenderTarget prevTarget = null;

    private static int targetW = 0;
    private static int targetH = 0;

    // PostChain caches
    private static PostChain cachedCombineChain   = null;
    private static PostChain cachedAccumMaxChain   = null;
    private static PostChain cachedAccumMixChain   = null;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    // Reflection cache for accumulation target lookups only
    private static Field cachedTargetsField = null;
    private static boolean targetsFieldSearched = false;

    // Public API

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);
        updateLockedWindowSize(fps, refreshRate);

        pushHistoryFrame(main);
        int sampleCount = Math.min(historyFilled, lockedN);
        if (sampleCount <= 1) return;

        PostChain chain = loadChain(client, "frame_blending");
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        int oldestIndex = oldestHistoryIndex(sampleCount);
        RenderTarget fallback = historyTargets[oldestIndex];
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget src = (i < sampleCount)
                    ? historyTargets[(oldestIndex + i) % MAX_HISTORY]
                    : fallback;
            setSampler(pass, "Sample" + i, new PersistentTextureInput("Sample" + i, src));
        }

        float invSampleCount = 1.0f / sampleCount;
        chain.process(main, allocator, (RenderPass rp) -> {
            trySetUniform(rp, "invSampleCount", new float[]{invSampleCount});
            trySetUniform(rp, new int[]{sampleCount});
        });
    }

    public static void applyAccumulationMax(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 7.0f, "accumulation_max");
    }

    public static void applyAccumulationMix(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 5.0f, "accumulation_mix");
    }

    public static void invalidate() {
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) {
                historyTargets[i].destroyBuffers();
                historyTargets[i] = null;
            }
        }
        if (prevTarget != null) {
            prevTarget.destroyBuffers();
            prevTarget = null;
        }
        targetW = 0;
        targetH = 0;
        historyWriteIndex = 0;
        historyFilled = 0;
        lockedN = 1;
        pendingN = 1;
        pendingFrames = 0;
        smoothedFPS = 0;
        cachedCombineChain = null;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        loadErrorLogged.clear();
        cachedTargetsField = null;
        targetsFieldSearched = false;
    }

    // Accumulation

    private static void applyAccumulationInternal(GraphicsResourceAllocator allocator,
                                                  float strength, String shaderName) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);

        PostChain chain = loadChain(client, shaderName);
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        // Inject our prevTarget directly as the "Prev" sampler (same technique as frame blending)
        if (prevTarget != null) {
            setSampler(pass, "Prev", new PersistentTextureInput("Prev", prevTarget));
        }

        float factor = strengthToBlendFactor(strength);
        chain.process(main, allocator, (RenderPass rp) -> trySetUniform(rp, "blendFactor", new float[]{factor}));

        // Save result for next frame
        copyFramebuffer(main, prevTarget);
    }

    private static float strengthToBlendFactor(float strength) {
        return (float) (1.0 - Math.pow(0.5, strength / 3.0));
    }

    // History management

    private static void pushHistoryFrame(RenderTarget src) {
        if (historyTargets[historyWriteIndex] == null) return;
        copyFramebuffer(src, historyTargets[historyWriteIndex]);
        historyWriteIndex = (historyWriteIndex + 1) % MAX_HISTORY;
        if (historyFilled < MAX_HISTORY) historyFilled++;
    }

    private static int oldestHistoryIndex(int sampleCount) {
        int idx = historyWriteIndex - sampleCount;
        if (idx < 0) idx += MAX_HISTORY;
        return idx;
    }

    private static void updateLockedWindowSize(float fps, int refreshRate) {
        if (fps > 0.0f) {
            smoothedFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }
        int desired = 1;
        if (smoothedFPS > 0.0f && refreshRate > 0) {
            desired = Math.clamp((int) Math.ceil(smoothedFPS / refreshRate), 1, MAX_HISTORY);
        }
        if (desired == lockedN) {
            pendingN = desired; pendingFrames = 0; return;
        }
        if (desired != pendingN) {
            pendingN = desired; pendingFrames = 1; return;
        }
        pendingFrames++;
        if (pendingFrames >= WINDOW_CHANGE_HOLD_FRAMES) {
            lockedN = pendingN; pendingFrames = 0;
        }
    }

    // Targets & copying

    private static void ensureTargets(int w, int h) {
        if (targetW == w && targetH == h && historyTargets[0] != null && prevTarget != null) return;
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) historyTargets[i].destroyBuffers();
            historyTargets[i] = new MainTarget(w, h);
        }
        if (prevTarget != null) prevTarget.destroyBuffers();
        prevTarget = new MainTarget(w, h);
        targetW = w;
        targetH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        pendingFrames = 0;
        pendingN = lockedN;
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

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void setSampler(PostPass pass, String samplerName, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            PostPass.Input input = inputs.get(i);
            if (input instanceof PostPass.TargetInput targetInput && samplerName.equals(targetInput.samplerName())) {
                inputs.set(i, replacement);
                return;
            }
            if (input instanceof PostPass.TextureInput textureInput && samplerName.equals(textureInput.samplerName())) {
                inputs.set(i, replacement);
                return;
            }
            if (input instanceof PersistentTextureInput persistentInput && samplerName.equals(persistentInput.samplerName)) {
                inputs.set(i, replacement);
                return;
            }
        }
    }

    private static class PersistentTextureInput implements PostPass.Input {
        private final String samplerName;
        private final RenderTarget target;

        PersistentTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target = target;
        }

        @Override
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            if (target.getColorTexture() != null) {
                pass.bindSampler(this.samplerName + "Sampler", target.getColorTexture());
            }
        }
    }

    // PostChain loading & targets

    private static PostChain loadChain(Minecraft client, String shaderName) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain result = cache.getOrLoadPostChain(
                    ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged.remove(shaderName);
            // Reset reflection cache if chain instance changed
            switch (shaderName) {
                case "frame_blending" -> { if (result != cachedCombineChain) { cachedCombineChain = result; resetTargetsFieldCache(); } }
                case "accumulation_max" -> { if (result != cachedAccumMaxChain) { cachedAccumMaxChain = result; resetTargetsFieldCache(); } }
                case "accumulation_mix" -> { if (result != cachedAccumMixChain) { cachedAccumMixChain = result; resetTargetsFieldCache(); } }
            }
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + shaderName + " shader: " + e.getMessage());
            return null;
        }
    }

    private static void resetTargetsFieldCache() {
        cachedTargetsField = null;
        targetsFieldSearched = false;
    }

    // Uniform helpers

    private static void trySetUniform(RenderPass pass, String name, float[] values) {
        try { pass.setUniform(name, values); } catch (Exception ignored) {}
    }

    private static void trySetUniform(RenderPass pass, int[] values) {
        try { pass.setUniform("activeCount", values); } catch (Exception ignored) {}
    }
}