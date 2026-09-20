package net.natural.motionblur.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(value = LevelRenderer.class, priority = 800)
public class MixinLevelRenderer {

    @Shadow @Final private LevelTargetBundle targets;

    @Shadow
    private void executeClassicTransparency(
            ChunkSectionsToRender chunkSectionsToRender,
            FeatureRenderDispatcher.PreparedFrame featureFrame,
            RenderPass renderPass) {
        throw new AssertionError();
    }

    @Unique private final Matrix4f prevModelView     = new Matrix4f();
    @Unique private final Matrix4f prevProjection    = new Matrix4f();
    @Unique private final Matrix4f scratchModelView  = new Matrix4f();
    @Unique private final Matrix4f scratchProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;
    @Unique private boolean previousFrameReady = false;
    @Unique private boolean naturalMotionBlur$mainPassSplit = false;
    @Unique private boolean naturalMotionBlur$insideClassicTransparencyReplacement = false;
    @Unique private RenderPass naturalMotionBlur$closedTerrainPass = null;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline, CameraRenderState cameraState,
            GpuBufferSlice terrainFog, Vector4f fogColor,
            boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {

        naturalMotionBlur$mainPassSplit = false;
        naturalMotionBlur$closedTerrainPass = null;
        Matrix4fc modelViewMatrix = cameraState.viewRotationMatrix;
        ConfigEntries config = ConfigManager.getConfig();
        boolean blurActive = config.enabled && config.getEffectiveMotionBlurStrength() != 0.0f;
        boolean recordingActive = config.recordingOverlayEnabled;
        boolean needsVelocityState = blurActive && config.usesVelocityBlur();

        double cx = cameraState.pos.x();
        double cy = cameraState.pos.y();
        double cz = cameraState.pos.z();

        if (!blurActive && !recordingActive) {
            ShaderManager.clearFrameAllocator();
            naturalMotionBlur$rememberCurrentFrameState(modelViewMatrix, cameraState.projectionMatrix, cx, cy, cz);
            return;
        }

        if (blurActive) {
            ShaderManager.captureAllocator(resourceAllocator);
        } else {
            ShaderManager.clearFrameAllocator();
        }
        if (recordingActive) {
            RecordingShaderManager.captureAllocator(resourceAllocator);
        }
        ShaderManager.beginFrame();

        if (!needsVelocityState) {
            naturalMotionBlur$rememberCurrentFrameState(modelViewMatrix, cameraState.projectionMatrix, cx, cy, cz);
            return;
        }

        scratchModelView.set(modelViewMatrix);
        scratchProjection.set(cameraState.projectionMatrix);
        ShaderManager.useCapturedWorldProjection(scratchProjection);

        if (!previousFrameReady) {
            ShaderManager.setFrameMotionBlur(
                    scratchModelView, scratchModelView,
                    scratchProjection, scratchProjection,
                    0.0f, 0.0f, 0.0f);

            naturalMotionBlur$rememberCurrentFrameState(scratchModelView, scratchProjection, cx, cy, cz);
            return;
        }

        float dx = (float)(cx - prevCamX);
        float dy = (float)(cy - prevCamY);
        float dz = (float)(cz - prevCamZ);

        ShaderManager.setFrameMotionBlur(
                scratchModelView, prevModelView,
                scratchProjection, prevProjection,
                dx, dy, dz);

        naturalMotionBlur$rememberCurrentFrameState(scratchModelView, scratchProjection, cx, cy, cz);
    }

    @Unique
    private void naturalMotionBlur$rememberCurrentFrameState(Matrix4fc modelViewMatrix, Matrix4fc projectionMatrix, double cx, double cy, double cz) {
        prevModelView.set(modelViewMatrix);
        prevProjection.set(projectionMatrix);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
        previousFrameReady = true;
    }

    @Redirect(
            method = "executeSolid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
            )
    )
    private void naturalMotionBlur$splitTerrainAndSolidFeatures(
            FeatureRenderDispatcher.PreparedFrame featureFrame,
            RenderPass terrainPass) {

        ConfigEntries config = ConfigManager.getConfig();
        boolean needsPreEntityVelocityPass = config.enabled
                && config.getEffectiveMotionBlurStrength() != 0.0f
                && config.usesVelocityBlur();

        if (!needsPreEntityVelocityPass) {
            naturalMotionBlur$mainPassSplit = false;
            naturalMotionBlur$closedTerrainPass = null;
            featureFrame.executeSolid(terrainPass);
            return;
        }

        naturalMotionBlur$mainPassSplit = true;
        naturalMotionBlur$closedTerrainPass = terrainPass;
        terrainPass.close();

        ShaderManager.applyPreEntityVelocityOnly(
                naturalMotionBlur$shouldUseSpecialSingleBlur());

        RenderTarget mainTarget = this.targets.main.get();
        assert mainTarget.getColorTextureView() != null;
        try (RenderPass featurePass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "NaturalMotionBlur / Solid features",
                        mainTarget.getColorTextureView(),
                        Optional.empty(),
                        mainTarget.getDepthTextureView(),
                        OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(featurePass);
            featureFrame.executeSolid(featurePass);
        }
    }

    @Inject(method = "executeClassicTransparency", at = @At("HEAD"), cancellable = true)
    private void naturalMotionBlur$continueClassicTransparencyAfterSplit(
            ChunkSectionsToRender chunkSectionsToRender,
            FeatureRenderDispatcher.PreparedFrame featureFrame,
            RenderPass closedTerrainPass,
            CallbackInfo ci) {

        if (!naturalMotionBlur$mainPassSplit
                || naturalMotionBlur$insideClassicTransparencyReplacement) {
            return;
        }

        if (closedTerrainPass != naturalMotionBlur$closedTerrainPass) {
            return;
        }

        RenderTarget mainTarget = this.targets.main.get();
        naturalMotionBlur$insideClassicTransparencyReplacement = true;
        try {
            assert mainTarget.getColorTextureView() != null;
            try (RenderPass transparencyPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            () -> "NaturalMotionBlur / Classic transparency",
                            mainTarget.getColorTextureView(),
                            Optional.empty(),
                            mainTarget.getDepthTextureView(),
                            OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(transparencyPass);
                executeClassicTransparency(
                        chunkSectionsToRender, featureFrame, transparencyPass);
            }
        } finally {
            naturalMotionBlur$insideClassicTransparencyReplacement = false;
        }

        naturalMotionBlur$closedTerrainPass = null;
        ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void naturalMotionBlur$onRenderLevelTail(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline, CameraRenderState cameraState,
            GpuBufferSlice terrainFog, Vector4f fogColor,
            boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();
        boolean specialSingleBlur = naturalMotionBlur$shouldUseSpecialSingleBlur();

        ShaderManager.applyDeferredIrisPreEntityVelocityOnly(specialSingleBlur);

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
            if (!specialSingleBlur) {
                ShaderManager.applyPostRenderVelocityOnly();
            }
        } else if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.VELOCITY_BASED && !specialSingleBlur) {
            ShaderManager.applyPostRenderVelocityOnly();
        }
    }

    @Unique
    private boolean naturalMotionBlur$shouldUseSpecialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) return true;
        return client.player != null && client.player.isPassenger();
    }
}