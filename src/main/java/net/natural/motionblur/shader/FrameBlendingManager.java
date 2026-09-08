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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrameBlendingManager {

    private static final int MAX_HISTORY = 24;
    private static final int FRAME_BLEND_SAMPLE_LIMIT = 12;
    private static final String MAIN_SAMPLER = "Main";
    private static final String PREV_SAMPLER = "Prev";

    private static final String[] SAMPLE_NAMES = new String[FRAME_BLEND_SAMPLE_LIMIT];
    static {
        for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    // Frame blending history
    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] historyInputs = new MutableTextureInput[MAX_HISTORY];
    private static final double[] historyTimestamps = new double[MAX_HISTORY];
    private static final int[] weightedHistoryIndices = new int[MAX_HISTORY];
    private static final float[] weightedHistoryWeights = new float[MAX_HISTORY];
    private static final int[] compactHistoryIndices = new int[MAX_HISTORY];
    private static final float[] compactHistoryWeights = new float[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled     = 0;
    private static float smoothedFPS    = 0;

    // Accumulation
    private static RenderTarget accumReadTarget = null;
    private static RenderTarget accumWriteTarget = null;
    private static MutableTextureInput mainInput = null;
    private static MutableTextureInput prevInput = null;
    private static boolean accumHasPrevious = false;

    private static int targetW = 0;
    private static int targetH = 0;

    // PostChain caches
    private static PostChain cachedCombineChain   = null;
    private static PostChain cachedAccumMaxChain   = null;
    private static PostChain cachedAccumMixChain   = null;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    // Public API

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate, float strength) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        updateSmoothedFPS(fps);

        if (refreshRate <= 0 || strength <= 0.0f) {
            historyWriteIndex = 0;
            historyFilled = 0;
            return;
        }

        ensureTargets(main.width, main.height);

        double now = currentTimeSeconds();
        pushHistoryFrame(main, now);

        int sampleCount = buildWeightedSampleList(now, refreshRate, smoothedFPS, strength);
        if (sampleCount <= 1) return;

        PostChain chain = loadChain(client, "frame_blending");
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        RenderTarget fallback = historyTargets[weightedHistoryIndices[sampleCount - 1]];
        for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) {
            RenderTarget src = (i < sampleCount)
                    ? historyTargets[weightedHistoryIndices[i]]
                    : fallback;
            MutableTextureInput input = historyInputs[i];
            if (input == null) {
                input = new MutableTextureInput(SAMPLE_NAMES[i], src);
                historyInputs[i] = input;
            } else {
                input.setTarget(src);
            }
            setSampler(pass, SAMPLE_NAMES[i], input);
        }

        float invTotalWeight = inverseTotalWeight(sampleCount);
        chain.process(main, allocator, (RenderPass rp) -> {
            trySetUniform(rp, "invTotalWeight", new float[]{invTotalWeight});
            trySetUniform(rp, new int[]{sampleCount});
            setSampleWeightUniforms(rp, sampleCount);
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
            historyInputs[i] = null;
            historyTimestamps[i] = 0.0;
        }
        if (accumReadTarget != null) {
            accumReadTarget.destroyBuffers();
            accumReadTarget = null;
        }
        if (accumWriteTarget != null) {
            accumWriteTarget.destroyBuffers();
            accumWriteTarget = null;
        }
        targetW = 0;
        targetH = 0;
        historyWriteIndex = 0;
        historyFilled = 0;
        smoothedFPS = 0;
        Arrays.fill(weightedHistoryIndices, 0);
        Arrays.fill(weightedHistoryWeights, 0.0f);
        Arrays.fill(compactHistoryIndices, 0);
        Arrays.fill(compactHistoryWeights, 0.0f);
        mainInput = null;
        prevInput = null;
        accumHasPrevious = false;
        cachedCombineChain = null;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        loadErrorLogged.clear();
    }

    // Accumulation

    private static void applyAccumulationInternal(GraphicsResourceAllocator allocator,
                                                  float strength, String shaderName) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);

        if (!accumHasPrevious) {
            copyFramebuffer(main, accumReadTarget);
            accumHasPrevious = true;
            return;
        }

        PostChain chain = loadChain(client, shaderName);
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        if (mainInput == null) {
            mainInput = new MutableTextureInput(MAIN_SAMPLER, main);
        } else {
            mainInput.setTarget(main);
        }
        setSampler(pass, MAIN_SAMPLER, mainInput);

        if (prevInput == null) {
            prevInput = new MutableTextureInput(PREV_SAMPLER, accumReadTarget);
        } else {
            prevInput.setTarget(accumReadTarget);
        }
        setSampler(pass, PREV_SAMPLER, prevInput);

        float factor = strengthToBlendFactor(strength);
        chain.process(accumWriteTarget, allocator, (RenderPass rp) -> trySetUniform(rp, "blendFactor", new float[]{factor}));

        copyFramebuffer(accumWriteTarget, main);
        swapAccumTargets();
    }

    private static float strengthToBlendFactor(float strength) {
        return (float) (1.0 - Math.pow(0.5, strength / 3.0));
    }

    // History management

    private static void pushHistoryFrame(RenderTarget src, double timestamp) {
        if (historyTargets[historyWriteIndex] == null) return;
        copyFramebuffer(src, historyTargets[historyWriteIndex]);
        historyTimestamps[historyWriteIndex] = timestamp;
        historyWriteIndex = (historyWriteIndex + 1) % MAX_HISTORY;
        if (historyFilled < MAX_HISTORY) historyFilled++;
    }

    private static void updateSmoothedFPS(float fps) {
        if (fps > 0.0f) {
            smoothedFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }
    }

    public static float getHybridVelocityStrength(float fps, int refreshRate, float strength) {
        float baseStrength = Math.max(0.0f, strength);
        if (baseStrength == 0.0f || fps <= 0.0f || refreshRate <= 0) return baseStrength;

        float referenceRate = Math.min(fps, (float) refreshRate);
        float frameSpan = baseStrength * (fps / referenceRate);
        float fillerStrength = Math.min(1.0f, frameSpan);

        float blendFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        float blendReferenceRate = Math.min(blendFPS, (float) refreshRate);
        float blendFrameSpan = baseStrength * (blendFPS / blendReferenceRate);
        int sampleCount = Math.min(MAX_HISTORY, (int)Math.ceil(blendFrameSpan - 0.000001f));
        sampleCount = Math.min(sampleCount, Math.min(MAX_HISTORY, historyFilled + 1));

        if (sampleCount > FRAME_BLEND_SAMPLE_LIMIT) {
            int maxGap = 1;
            int previous = 0;
            for (int out = 1; out < FRAME_BLEND_SAMPLE_LIMIT; out++) {
                int source = Math.round((float)out * (sampleCount - 1) / (FRAME_BLEND_SAMPLE_LIMIT - 1));
                maxGap = Math.max(maxGap, source - previous);
                previous = source;
            }
            fillerStrength = Math.max(fillerStrength, maxGap);
        }

        return fillerStrength;
    }

    private static int buildWeightedSampleList(double exposureEnd, int refreshRate, float fps, float strength) {
        Arrays.fill(weightedHistoryWeights, 0.0f);
        if (historyFilled <= 0) return 0;

        double referenceRate = (fps > 0.0f)
                ? Math.min(fps, (double) refreshRate)
                : (double) refreshRate;
        double exposureDuration = Math.max(0.0, strength) / referenceRate;
        double exposureStart = exposureEnd - exposureDuration;
        double estimatedFrameTime = (fps > 0.0f) ? 1.0 / fps : 1.0 / refreshRate;
        double totalWeight = 0.0;
        int sampleCount = 0;
        int firstIndex = historyWriteIndex - historyFilled;
        if (firstIndex < 0) firstIndex += MAX_HISTORY;

        for (int i = 0; i < historyFilled; i++) {
            int idx = (firstIndex + i) % MAX_HISTORY;
            double frameEnd = historyTimestamps[idx];
            if (frameEnd <= 0.0) continue;

            double frameStart;
            if (i > 0) {
                int prevIdx = (firstIndex + i - 1) % MAX_HISTORY;
                frameStart = historyTimestamps[prevIdx];
            } else {
                frameStart = frameEnd - estimatedFrameTime;
            }
            if (frameStart >= frameEnd) frameStart = frameEnd - estimatedFrameTime;

            double overlap = Math.min(frameEnd, exposureEnd) - Math.max(frameStart, exposureStart);
            if (overlap > 0.0000001) {
                weightedHistoryIndices[sampleCount] = idx;
                weightedHistoryWeights[sampleCount] = (float)overlap;
                totalWeight += overlap;
                sampleCount++;
            }
        }

        if (sampleCount <= 0) return 0;
        if (totalWeight <= 0.0000001) return 0;

        if (sampleCount > FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT) {
            sampleCount = compactWeightedSampleList(sampleCount);
        }

        return sampleCount;
    }

    private static int compactWeightedSampleList(int sampleCount) {
        if (sampleCount <= FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT || FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT <= 0) return sampleCount;
        if (FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT == 1) {
            float totalWeight = 0.0f;
            for (int i = 0; i < sampleCount; i++) totalWeight += weightedHistoryWeights[i];
            weightedHistoryIndices[0] = weightedHistoryIndices[sampleCount - 1];
            weightedHistoryWeights[0] = totalWeight;
            for (int i = 1; i < sampleCount; i++) weightedHistoryWeights[i] = 0.0f;
            return 1;
        }

        Arrays.fill(compactHistoryWeights, 0.0f);

        for (int out = 0; out < FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT; out++) {
            int source = Math.round((float) out * (sampleCount - 1) / (FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT - 1));
            compactHistoryIndices[out] = weightedHistoryIndices[source];
        }

        for (int source = 0; source < sampleCount; source++) {
            int out = Math.round((float) source * (FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT - 1) / (sampleCount - 1));
            if (out < 0) out = 0;
            if (out >= FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT) out = FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT - 1;
            compactHistoryWeights[out] += weightedHistoryWeights[source];
        }

        for (int i = 0; i < FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT; i++) {
            weightedHistoryIndices[i] = compactHistoryIndices[i];
            weightedHistoryWeights[i] = compactHistoryWeights[i];
        }
        for (int i = FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT; i < sampleCount; i++) {
            weightedHistoryWeights[i] = 0.0f;
        }

        return FrameBlendingManager.FRAME_BLEND_SAMPLE_LIMIT;
    }

    private static float inverseTotalWeight(int sampleCount) {
        float totalWeight = 0.0f;
        for (int i = 0; i < sampleCount; i++) {
            totalWeight += weightedHistoryWeights[i];
        }
        return totalWeight > 0.0f ? 1.0f / totalWeight : 1.0f;
    }

    private static double currentTimeSeconds() {
        return System.nanoTime() * 1.0E-9;
    }

    // Targets & copying

    private static void ensureTargets(int w, int h) {
        if (targetW == w && targetH == h && historyTargets[0] != null && accumReadTarget != null && accumWriteTarget != null) return;
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) historyTargets[i].destroyBuffers();
            historyTargets[i] = new MainTarget(w, h);
            historyInputs[i] = null;
            historyTimestamps[i] = 0.0;
        }
        if (accumReadTarget != null) accumReadTarget.destroyBuffers();
        if (accumWriteTarget != null) accumWriteTarget.destroyBuffers();
        accumReadTarget = new MainTarget(w, h);
        accumWriteTarget = new MainTarget(w, h);
        targetW = w;
        targetH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        mainInput = null;
        prevInput = null;
        accumHasPrevious = false;
    }

    private static void swapAccumTargets() {
        RenderTarget temp = accumReadTarget;
        accumReadTarget = accumWriteTarget;
        accumWriteTarget = temp;
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
                if (input != replacement) inputs.set(i, replacement);
                return;
            }
            if (input instanceof PostPass.TextureInput textureInput && samplerName.equals(textureInput.samplerName())) {
                if (input != replacement) inputs.set(i, replacement);
                return;
            }
            if (input instanceof MutableTextureInput persistentInput && samplerName.equals(persistentInput.samplerName)) {
                if (input != replacement) inputs.set(i, replacement);
                return;
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

    // PostChain loading

    private static PostChain loadChain(Minecraft client, String shaderName) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;
            PostChain result = cache.getOrLoadPostChain(
                    ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged.remove(shaderName);
            switch (shaderName) {
                case "frame_blending" -> { if (result != cachedCombineChain) cachedCombineChain = result; }
                case "accumulation_max" -> { if (result != cachedAccumMaxChain) cachedAccumMaxChain = result; }
                case "accumulation_mix" -> { if (result != cachedAccumMixChain) cachedAccumMixChain = result; }
            }
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + shaderName + " shader: " + e.getMessage());
            return null;
        }
    }

    // Uniform helpers


    private static void setSampleWeightUniforms(RenderPass pass, int sampleCount) {
        for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) {
            float weight = i < sampleCount ? FrameBlendingManager.weightedHistoryWeights[i] : 0.0f;
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