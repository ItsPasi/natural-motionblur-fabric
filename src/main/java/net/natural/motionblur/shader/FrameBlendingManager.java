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
import net.natural.motionblur.util.GpuBufferUtil;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrameBlendingManager {

    private static final int UBO_SIZE = 16;
    private static final int MAX_HISTORY = 8;
    private static final int WINDOW_CHANGE_HOLD_FRAMES = 6;

    private static final String PREV_SAMPLER = "Prev";

    private static PostChain cachedCombineChain = null;
    private static GpuBuffer combineUBO = null;

    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled     = 0;

    private static int   lockedN        = 1;
    private static int   pendingN       = 1;
    private static int   pendingFrames  = 0;
    private static float smoothedFPS    = 0;

    // Accumulation MAX/MIX
    private static PostChain cachedAccumMaxChain = null;
    private static PostChain cachedAccumMixChain = null;
    private static GpuBuffer accumSimpleUBO      = null;
    private static RenderTarget prevTarget       = null;
    private static PersistentTextureInput injectedPrevInput = null;

    private static int targetW = 0;
    private static int targetH = 0;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        ensureTargets(main.width, main.height);
        updateLockedWindowSize(fps, refreshRate);

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
        if (!combineUniforms.containsKey("FrameBlendParamsUniforms")) return;
        if (combineUBO == null) combineUBO = GpuBufferUtil.createUBO("FrameBlendParamsUniforms", UBO_SIZE);
        replaceUBO(combineUniforms, "FrameBlendParamsUniforms", combineUBO);
        writeBlendParamsUBO(combineUBO, 1.0f / sampleCount, sampleCount);

        RenderTarget fallback = historyTargets[oldestIndex];
        for (int i = 0; i < MAX_HISTORY; i++) {
            RenderTarget target = (i < sampleCount)
                    ? historyTargets[(oldestIndex + i) % MAX_HISTORY]
                    : fallback;
            setSampler(combinePass, "Sample" + i, new PersistentTextureInput("Sample" + i, target));
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
        combineUBO = null;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        accumSimpleUBO = null;
        injectedPrevInput = null;
        loadErrorLogged.clear();
    }

    private static void updateLockedWindowSize(float fps, int refreshRate) {
        if (fps > 0.0f) {
            smoothedFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }

        int desired = 1;
        if (smoothedFPS > 0.0f && refreshRate > 0) {
            desired = Math.max(1, Math.min(MAX_HISTORY, Math.round(smoothedFPS / refreshRate)));
        }

        if (desired == lockedN) {
            pendingN = desired;
            pendingFrames = 0;
            return;
        }

        if (desired != pendingN) {
            pendingN = desired;
            pendingFrames = 1;
            return;
        }

        pendingFrames++;
        if (pendingFrames >= WINDOW_CHANGE_HOLD_FRAMES) {
            lockedN = pendingN;
            pendingFrames = 0;
        }
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
        if (!uniforms.containsKey("AccumulationUniforms")) return;

        if (accumSimpleUBO == null) accumSimpleUBO = GpuBufferUtil.createUBO("AccumulationUniforms", UBO_SIZE);
        replaceUBO(uniforms, "AccumulationUniforms", accumSimpleUBO);
        writeFloatUBO(accumSimpleUBO, strengthToBlendFactor(strength));

        if (injectedPrevInput == null)
            injectedPrevInput = new PersistentTextureInput(PREV_SAMPLER, prevTarget);
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
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, shaderName),
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
        }
        if (prevTarget != null) prevTarget.destroyBuffers();
        prevTarget = new MainTarget(w, h);
        targetW = w;
        targetH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        pendingFrames = 0;
        pendingN = lockedN;
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
                inputs.set(i, replacement);
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

            PostChain result = cache.getOrLoadPostChain(
                    Identifier.fromNamespaceAndPath(NaturalMotionBlurMod.ID, "frame_blending"),
                    LevelTargetBundle.MAIN_TARGETS);

            if (result != cachedCombineChain) {
                cachedCombineChain = result;
                combineUBO = null;
            }

            loadErrorLogged.remove("frame_blending");
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add("frame_blending"))
                System.err.println("[NaturalMotionBlur] Failed to load " + "frame_blending" + " shader: " + e.getMessage());
            return null;
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
