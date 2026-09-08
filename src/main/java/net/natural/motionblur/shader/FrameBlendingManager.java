package net.natural.motionblur.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
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
import net.natural.motionblur.util.ClientRenderTargets;
import net.natural.motionblur.util.GpuBufferUtil;
import net.natural.motionblur.util.ManagedUniformBuffer;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FrameBlendingManager {

    private static final int FRAME_BLEND_UBO_SIZE = 112;
    private static final int ACCUM_UBO_SIZE = 16;
    private static final int MAX_HISTORY = 24;
    private static final int GL_HISTORY_LIMIT = 12;
    private static final int UBO_RING_SIZE = 3;

    private static final String MAIN_SAMPLER = "Main";
    private static final String PREV_SAMPLER = "Prev";
    private static final String FRAME_BLEND_UBO = "FrameBlendParamsUniforms";
    private static final String ACCUM_UBO = "AccumulationUniforms";

    private static final Identifier FRAME_BLENDING_ID = Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "frame_blending");
    private static final Identifier FRAME_BLENDING_GL_ID = Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "frame_blending_gl");
    private static final Identifier ACCUMULATION_MAX_ID = Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "accumulation_max");
    private static final Identifier ACCUMULATION_MIX_ID = Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "accumulation_mix");

    private static final String[] SAMPLE_NAMES = new String[MAX_HISTORY];
    static {
        for (int i = 0; i < MAX_HISTORY; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static PostChain cachedCombineChain = null;
    private static final ManagedUniformBuffer.Ring combineUBORing = new ManagedUniformBuffer.Ring(FRAME_BLEND_UBO, FRAME_BLEND_UBO_SIZE, UBO_RING_SIZE);

    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] historyInputs = new MutableTextureInput[MAX_HISTORY];
    private static final double[] historyTimestamps = new double[MAX_HISTORY];
    private static final int[] weightedHistoryIndices = new int[MAX_HISTORY];
    private static final float[] weightedHistoryWeights = new float[MAX_HISTORY];
    // Scratch storage used when the requested exposure contains more history frames
    // than the active backend can bind at once (OpenGL is limited to 12 here).
    // We compact the full temporal window into the available samples instead of
    // dropping the oldest frames, so increasing strength still increases blur length.
    private static final int[] compactHistoryIndices = new int[MAX_HISTORY];
    private static final float[] compactHistoryWeights = new float[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled     = 0;

    private static float smoothedFPS    = 0;

    // Accumulation MAX/MIX
    private static PostChain cachedAccumMaxChain = null;
    private static PostChain cachedAccumMixChain = null;
    private static final ManagedUniformBuffer accumSimpleUBO = new ManagedUniformBuffer(ACCUM_UBO, ACCUM_UBO_SIZE);
    private static RenderTarget accumReadTarget  = null;
    private static RenderTarget accumWriteTarget = null;
    private static MutableTextureInput injectedMainInput = null;
    private static MutableTextureInput injectedPrevInput = null;
    private static boolean accumHasPrevious = false;

    private static int historyW = 0;
    private static int historyH = 0;
    private static int accumW = 0;
    private static int accumH = 0;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate, float strength) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);
        updateSmoothedFPS(fps);

        if (refreshRate <= 0 || strength <= 0.0f) {
            historyWriteIndex = 0;
            historyFilled = 0;
            return;
        }

        ensureHistoryTargets(main.width, main.height);

        double now = currentTimeSeconds();
        pushHistoryFrame(main, now);

        int activeMaxHistory = activeFrameBlendSampleLimit();
        int sampleCount = buildWeightedSampleList(now, refreshRate, smoothedFPS, activeMaxHistory, strength);
        if (sampleCount <= 1) {
            return;
        }

        PostChain combineChain = loadFrameBlendChain(client, activeMaxHistory);
        if (combineChain == null) return;
        PostPass combinePass = firstPass(combineChain);
        if (combinePass == null) return;
        Map<String, GpuBuffer> combineUniforms = ((PostPassAccessor) combinePass).getCustomUniforms();
        if (!combineUniforms.containsKey(FRAME_BLEND_UBO)) return;

        GpuBuffer combineUBO = combineUBORing.putNext(combineChain, combineUniforms, FRAME_BLEND_UBO);

        try {
            writeBlendParamsUBO(combineUBO, inverseTotalWeight(sampleCount), sampleCount);

            RenderTarget fallback = historyTargets[weightedHistoryIndices[sampleCount - 1]];
            for (int i = 0; i < activeMaxHistory; i++) {
                RenderTarget target = (i < sampleCount)
                        ? historyTargets[weightedHistoryIndices[i]]
                        : fallback;
                MutableTextureInput input = historyInputs[i];
                if (input == null) {
                    input = new MutableTextureInput(SAMPLE_NAMES[i], target);
                    historyInputs[i] = input;
                } else {
                    input.setTarget(target);
                }
                setSampler(combinePass, SAMPLE_NAMES[i], input);
            }

            combineChain.process(main, allocator);
        } catch (RuntimeException e) {
            if (combineUBORing.resetIfClosed(e)) return;
            throw e;
        }
    }

    public static void applyAccumulationMax(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 7.0f, "accumulation_max", true);
    }

    public static void applyAccumulationMix(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 5.0f, "accumulation_mix", false);
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
        combineUBORing.reset();
        accumSimpleUBO.reset();
        historyW = 0;
        historyH = 0;
        accumW = 0;
        accumH = 0;
        historyWriteIndex = 0;
        historyFilled = 0;
        smoothedFPS = 0;
        Arrays.fill(weightedHistoryIndices, 0);
        Arrays.fill(weightedHistoryWeights, 0.0f);
        Arrays.fill(compactHistoryIndices, 0);
        Arrays.fill(compactHistoryWeights, 0.0f);
        cachedCombineChain = null;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        injectedMainInput = null;
        injectedPrevInput = null;
        accumHasPrevious = false;
        loadErrorLogged.clear();
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
        int sampleLimit = activeFrameBlendSampleLimit();

        if (sampleCount > sampleLimit) {
            int maxGap = 1;
            int previous = 0;
            for (int out = 1; out < sampleLimit; out++) {
                int source = Math.round((float)out * (sampleCount - 1) / (sampleLimit - 1));
                maxGap = Math.max(maxGap, source - previous);
                previous = source;
            }
            fillerStrength = Math.max(fillerStrength, maxGap);
        }

        return fillerStrength;
    }

    private static void pushHistoryFrame(RenderTarget src, double timestamp) {
        if (historyTargets[historyWriteIndex] == null) return;
        copyTexture(src, historyTargets[historyWriteIndex]);
        historyTimestamps[historyWriteIndex] = timestamp;
        historyWriteIndex = (historyWriteIndex + 1) % MAX_HISTORY;
        if (historyFilled < MAX_HISTORY) historyFilled++;
    }

    private static int buildWeightedSampleList(double exposureEnd, int refreshRate, float fps, int maxSamples, float strength) {
        Arrays.fill(weightedHistoryWeights, 0.0f);
        if (historyFilled <= 0) return 0;

        // Match Velocity Based + Refresh Rate Scaling
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

        if (sampleCount > maxSamples) {
            sampleCount = compactWeightedSampleList(sampleCount, maxSamples);
        }

        return sampleCount;
    }

    private static int compactWeightedSampleList(int sampleCount, int maxSamples) {
        if (sampleCount <= maxSamples || maxSamples <= 0) return sampleCount;
        if (maxSamples == 1) {
            float totalWeight = 0.0f;
            for (int i = 0; i < sampleCount; i++) totalWeight += weightedHistoryWeights[i];
            weightedHistoryIndices[0] = weightedHistoryIndices[sampleCount - 1];
            weightedHistoryWeights[0] = totalWeight;
            for (int i = 1; i < sampleCount; i++) weightedHistoryWeights[i] = 0.0f;
            return 1;
        }

        Arrays.fill(compactHistoryWeights, 0.0f);

        // Select evenly distributed source frames
        for (int out = 0; out < maxSamples; out++) {
            int source = Math.round((float) out * (sampleCount - 1) / (maxSamples - 1));
            compactHistoryIndices[out] = weightedHistoryIndices[source];
        }

        for (int source = 0; source < sampleCount; source++) {
            int out = Math.round((float) source * (maxSamples - 1) / (sampleCount - 1));
            if (out < 0) out = 0;
            if (out >= maxSamples) out = maxSamples - 1;
            compactHistoryWeights[out] += weightedHistoryWeights[source];
        }

        for (int i = 0; i < maxSamples; i++) {
            weightedHistoryIndices[i] = compactHistoryIndices[i];
            weightedHistoryWeights[i] = compactHistoryWeights[i];
        }
        for (int i = maxSamples; i < sampleCount; i++) {
            weightedHistoryWeights[i] = 0.0f;
        }

        return maxSamples;
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

    // Accumulation MAX/MIX
    private static void applyAccumulationInternal(GraphicsResourceAllocator allocator,
                                                  float strength, String shaderName, boolean isMax) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);
        ensureAccumTargets(main.width, main.height);

        if (!accumHasPrevious) {
            copyTexture(main, accumReadTarget);
            accumHasPrevious = true;
            return;
        }

        PostChain chain = loadAccumSimpleChain(client, shaderName, isMax);
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).getCustomUniforms();
        if (!uniforms.containsKey(ACCUM_UBO)) return;

        GpuBuffer ubo = accumSimpleUBO.put(chain, uniforms, ACCUM_UBO);

        try {
            writeFloatUBO(ubo, strengthToBlendFactor(strength));

            if (injectedMainInput == null) {
                injectedMainInput = new MutableTextureInput(MAIN_SAMPLER, main);
            } else {
                injectedMainInput.setTarget(main);
            }
            setSampler(pass, MAIN_SAMPLER, injectedMainInput);

            if (injectedPrevInput == null) {
                injectedPrevInput = new MutableTextureInput(PREV_SAMPLER, accumReadTarget);
            } else {
                injectedPrevInput.setTarget(accumReadTarget);
            }
            setSampler(pass, PREV_SAMPLER, injectedPrevInput);

            chain.process(accumWriteTarget, allocator);
            copyTexture(accumWriteTarget, main);
            swapAccumTargets();
        } catch (RuntimeException e) {
            if (accumSimpleUBO.resetIfClosed(e)) return;
            throw e;
        }
    }

    private static float strengthToBlendFactor(float strength) {
        return (float) (1.0 - Math.pow(0.5, strength / 3.0));
    }

    private static PostChain loadAccumSimpleChain(Minecraft client, String shaderName, boolean isMax) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;

            PostChain result = cache.getOrLoadPostChain(isMax ? ACCUMULATION_MAX_ID : ACCUMULATION_MIX_ID, LevelTargetBundle.MAIN_TARGETS);

            if (isMax && result != cachedAccumMaxChain) {
                cachedAccumMaxChain = result;
                injectedMainInput = null;
                injectedPrevInput = null;
            } else if (!isMax && result != cachedAccumMixChain) {
                cachedAccumMixChain = result;
                injectedMainInput = null;
                injectedPrevInput = null;
            }

            loadErrorLogged.remove(shaderName);
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName))
                System.err.println("[NaturalMotionBlur] Failed to load " + shaderName + " shader: " + e.getMessage());
            return null;
        }
    }

    private static void ensureHistoryTargets(int w, int h) {
        if (historyW == w && historyH == h && historyTargets[0] != null) return;
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) historyTargets[i].destroyBuffers();
            historyTargets[i] = new MainTarget(w, h);
            historyInputs[i] = null;
            historyTimestamps[i] = 0.0;
        }
        historyW = w;
        historyH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        Arrays.fill(weightedHistoryIndices, 0);
        Arrays.fill(weightedHistoryWeights, 0.0f);
    }

    private static void ensureAccumTargets(int w, int h) {
        if (accumW == w && accumH == h && accumReadTarget != null && accumWriteTarget != null) return;
        if (accumReadTarget != null) accumReadTarget.destroyBuffers();
        if (accumWriteTarget != null) accumWriteTarget.destroyBuffers();
        accumReadTarget = new MainTarget(w, h);
        accumWriteTarget = new MainTarget(w, h);
        accumW = w;
        accumH = h;
        injectedMainInput = null;
        injectedPrevInput = null;
        accumHasPrevious = false;
    }

    private static void swapAccumTargets() {
        RenderTarget temp = accumReadTarget;
        accumReadTarget = accumWriteTarget;
        accumWriteTarget = temp;
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        assert src.getColorTexture() != null;
        assert dst.getColorTexture() != null;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, dst.width, dst.height);
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).getPasses();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void setSampler(PostPass pass, String samplerName, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (samplerName.equals(inputs.get(i).samplerName())) {
                if (inputs.get(i) != replacement) inputs.set(i, replacement);
                return;
            }
        }
    }

    private static void writeFloatUBO(GpuBuffer ubo, float value) {
        GpuBufferUtil.writeStd140(ubo, ACCUM_UBO_SIZE, b -> {
            b.putFloat(value);
            b.putInt(0);
            b.putInt(0);
            b.putInt(0);
        });
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, float invTotalWeight, int sampleCount) {
        GpuBufferUtil.writeStd140(ubo, FRAME_BLEND_UBO_SIZE, b -> {
            b.putFloat(invTotalWeight);
            b.putInt(sampleCount);
            for (int i = 0; i < MAX_HISTORY; i++) {
                b.putFloat(i < sampleCount ? weightedHistoryWeights[i] : 0.0f);
            }
            b.putFloat(0.0f);
            b.putFloat(0.0f);
        });
    }

    private static PostChain loadFrameBlendChain(Minecraft client, int activeMaxHistory) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;

            PostChain result = cache.getOrLoadPostChain(activeMaxHistory <= GL_HISTORY_LIMIT ? FRAME_BLENDING_GL_ID : FRAME_BLENDING_ID, LevelTargetBundle.MAIN_TARGETS);

            if (result != cachedCombineChain) {
                cachedCombineChain = result;
            }

            loadErrorLogged.remove("frame_blending");
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add("frame_blending"))
                System.err.println("[NaturalMotionBlur] Failed to load frame_blending shader: " + e.getMessage());
            return null;
        }
    }

    private static int activeFrameBlendSampleLimit() {
        return isOpenGlBackend() ? GL_HISTORY_LIMIT : MAX_HISTORY;
    }

    private static boolean isOpenGlBackend() {
        try {
            Object device = RenderSystem.getDevice();
            String name = device.getClass().getName().toLowerCase(Locale.ROOT);
            Object backend = findBackend(device);
            if (backend != null) name += " " + backend.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("vulkan")) return false;
            return name.contains("opengl") || name.contains("gl");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findBackend(Object device) {
        for (Class<?> c = device.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("backend");
                field.setAccessible(true);
                return field.get(device);
            } catch (Throwable ignored) {
            }
        }
        return null;
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
        public void addToPass(@NonNull FramePass pass,
                              @NonNull Map<Identifier, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @NonNull GpuTextureView texture(@NonNull Map<Identifier, ResourceHandle<RenderTarget>> targets) {
            assert target.getColorTextureView() != null;
            return target.getColorTextureView();
        }

        @Override public @NonNull String samplerName() { return samplerName; }
        @Override public boolean bilinear() { return false; }
    }
}