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
import net.minecraft.resources.ResourceLocation;
import net.natural.motionblur.NaturalMotionBlurMod;
import net.natural.motionblur.mixin.PostChainAccessor;
import net.natural.motionblur.mixin.PostPassAccessor;
import net.natural.motionblur.mixin.ShaderManagerAccessor;
import net.natural.motionblur.util.GpuBufferUtil;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrameBlendingManager {

    private static final int UBO_SIZE = 16;
    private static final int MAX_HISTORY = 8;
    private static final int UBO_RING_SIZE = 3;

    private static final String PREV_SAMPLER = "Prev";
    private static final String FRAME_BLEND_UBO = "FrameBlendParamsUniforms";
    private static final String ACCUM_UBO = "AccumulationUniforms";

    private static final ResourceLocation FRAME_BLENDING_ID = ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "frame_blending");
    private static final ResourceLocation ACCUMULATION_MAX_ID = ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "accumulation_max");
    private static final ResourceLocation ACCUMULATION_MIX_ID = ResourceLocation.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "accumulation_mix");

    private static final String[] SAMPLE_NAMES = new String[MAX_HISTORY];
    static {
        for (int i = 0; i < MAX_HISTORY; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static PostChain cachedCombineChain = null;
    private static final GpuBuffer[] combineUBORing = new GpuBuffer[UBO_RING_SIZE];
    private static int combineUBOIndex = 0;

    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] historyInputs = new MutableTextureInput[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled     = 0;

    private static int   lockedN        = 1;
    private static float smoothedFPS    = 0;

    // Accumulation MAX/MIX
    private static PostChain cachedAccumMaxChain = null;
    private static PostChain cachedAccumMixChain = null;
    private static GpuBuffer accumSimpleUBO      = null;
    private static RenderTarget prevTarget       = null;
    private static MutableTextureInput injectedPrevInput = null;

    private static int targetW = 0;
    private static int targetH = 0;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        updateLockedWindowSize(fps, refreshRate);

        if (lockedN <= 1) {
            historyWriteIndex = 0;
            historyFilled = 0;
            return;
        }

        ensureTargets(main.width, main.height);

        pushHistoryFrame(main);
        int sampleCount = Math.min(historyFilled, lockedN);
        if (sampleCount <= 1) {
            return;
        }

        int oldestIndex = oldestHistoryIndex(sampleCount);

        PostChain combineChain = loadFrameBlendChain(client);
        if (combineChain == null) return;
        PostPass combinePass = firstPass(combineChain);
        if (combinePass == null) return;
        Map<String, GpuBuffer> combineUniforms = ((PostPassAccessor) combinePass).getCustomUniforms();
        if (!combineUniforms.containsKey(FRAME_BLEND_UBO)) return;

        GpuBuffer combineUBO = nextCombineUBO();
        replaceFrameBlendUBO(combineUniforms, combineUBO);
        writeBlendParamsUBO(combineUBO, 1.0f / sampleCount, sampleCount);

        RenderTarget fallback = historyTargets[oldestIndex];
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget target = (i < sampleCount)
                    ? historyTargets[(oldestIndex + i) % MAX_HISTORY]
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
        }
        if (prevTarget != null) {
            prevTarget.destroyBuffers();
            prevTarget = null;
        }
        for (int i = 0; i < combineUBORing.length; i++) {
            if (combineUBORing[i] != null) {
                combineUBORing[i].close();
                combineUBORing[i] = null;
            }
        }
        if (accumSimpleUBO != null) {
            accumSimpleUBO.close();
            accumSimpleUBO = null;
        }
        targetW = 0;
        targetH = 0;
        historyWriteIndex = 0;
        historyFilled = 0;
        lockedN = 1;
        smoothedFPS = 0;
        cachedCombineChain = null;
        combineUBOIndex = 0;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        injectedPrevInput = null;
        loadErrorLogged.clear();
    }

    private static void updateLockedWindowSize(float fps, int refreshRate) {
        if (fps > 0.0f) {
            smoothedFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }

        int desired = 1;
        if (smoothedFPS > 0.0f && refreshRate > 0) {
            desired = Math.clamp((int)Math.ceil(smoothedFPS / refreshRate), 1, MAX_HISTORY);
        }

        lockedN = desired;
    }

    private static void pushHistoryFrame(RenderTarget src) {
        if (historyTargets[historyWriteIndex] == null) return;
        copyTexture(src, historyTargets[historyWriteIndex]);
        historyWriteIndex = (historyWriteIndex + 1) % MAX_HISTORY;
        if (historyFilled < MAX_HISTORY) historyFilled++;
    }

    private static int oldestHistoryIndex(int sampleCount) {
        int idx = historyWriteIndex - sampleCount;
        if (idx < 0) idx += MAX_HISTORY;
        return idx;
    }

    // Accumulation MAX/MIX
    private static void applyAccumulationInternal(GraphicsResourceAllocator allocator,
                                                  float strength, String shaderName, boolean isMax) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);

        PostChain chain = loadAccumSimpleChain(client, shaderName, isMax);
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).getCustomUniforms();
        if (!uniforms.containsKey(ACCUM_UBO)) return;

        if (accumSimpleUBO == null) accumSimpleUBO = GpuBufferUtil.createUBO(ACCUM_UBO, UBO_SIZE);
        replaceUBO(uniforms, accumSimpleUBO);
        writeFloatUBO(accumSimpleUBO, strengthToBlendFactor(strength));

        if (injectedPrevInput == null) {
            injectedPrevInput = new MutableTextureInput(PREV_SAMPLER, prevTarget);
        } else {
            injectedPrevInput.setTarget(prevTarget);
        }
        setSampler(pass, PREV_SAMPLER, injectedPrevInput);

        chain.process(main, allocator);
        copyTexture(main, prevTarget);
    }

    private static float strengthToBlendFactor(float strength) {
        return (float) (1.0 - Math.pow(0.5, strength / 3.0));
    }

    private static PostChain loadAccumSimpleChain(Minecraft client, String shaderName, boolean isMax) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;

            PostChain result = cache.getOrLoadPostChain(
                    isMax ? ACCUMULATION_MAX_ID : ACCUMULATION_MIX_ID,
                    LevelTargetBundle.MAIN_TARGETS);

            if (isMax && result != cachedAccumMaxChain) {
                cachedAccumMaxChain = result;
                accumSimpleUBO = null;
                injectedPrevInput = null;
            } else if (!isMax && result != cachedAccumMixChain) {
                cachedAccumMixChain = result;
                accumSimpleUBO = null;
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

    private static void ensureTargets(int w, int h) {
        if (targetW == w && targetH == h && historyTargets[0] != null && prevTarget != null) return;
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) historyTargets[i].destroyBuffers();
            historyTargets[i] = new MainTarget(w, h);
            historyInputs[i] = null;
        }
        if (prevTarget != null) prevTarget.destroyBuffers();
        prevTarget = new MainTarget(w, h);
        targetW = w;
        targetH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        injectedPrevInput = null;
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

    private static void replaceUBO(Map<String, GpuBuffer> map, GpuBuffer ubo) {
        GpuBuffer old = map.get(FrameBlendingManager.ACCUM_UBO);
        if (old == ubo) return;
        old = map.put(FrameBlendingManager.ACCUM_UBO, ubo);
        if (old != null && old != ubo) old.close();
    }

    private static void replaceFrameBlendUBO(Map<String, GpuBuffer> map, GpuBuffer ubo) {
        GpuBuffer old = map.get(FRAME_BLEND_UBO);
        if (old == ubo) return;
        old = map.put(FRAME_BLEND_UBO, ubo);
        if (old != null && old != ubo && !isOwnedFrameBlendUBO(old)) old.close();
    }

    private static boolean isOwnedFrameBlendUBO(GpuBuffer buffer) {
        for (GpuBuffer owned : combineUBORing) {
            if (owned == buffer) return true;
        }
        return false;
    }

    private static GpuBuffer nextCombineUBO() {
        GpuBuffer ubo = combineUBORing[combineUBOIndex];
        if (ubo == null) {
            ubo = GpuBufferUtil.createUBO(FRAME_BLEND_UBO, UBO_SIZE);
            combineUBORing[combineUBOIndex] = ubo;
        }
        combineUBOIndex = (combineUBOIndex + 1) % UBO_RING_SIZE;
        return ubo;
    }

    private static void writeFloatUBO(GpuBuffer ubo, float value) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putFloat(value);
            b.putInt(0);
            b.putInt(0);
            b.putInt(0);
        }
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, float invSampleCount, int sampleCount) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putFloat(invSampleCount);
            b.putInt(sampleCount);
            b.putInt(0);
            b.putInt(0);
        }
    }

    private static PostChain loadFrameBlendChain(Minecraft client) {
        try {
            net.minecraft.client.renderer.ShaderManager.CompilationCache cache =
                    ((ShaderManagerAccessor) client.getShaderManager()).getCompilationCache();
            if (cache == null) return null;

            PostChain result = cache.getOrLoadPostChain(FRAME_BLENDING_ID, LevelTargetBundle.MAIN_TARGETS);

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
                              @NonNull Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public @NonNull GpuTextureView texture(@NonNull Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            assert target.getColorTextureView() != null;
            return target.getColorTextureView();
        }

        @Override public @NonNull String samplerName() { return samplerName; }
    }
}