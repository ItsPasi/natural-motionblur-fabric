package net.natural.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Unique private final Matrix4f prevModelView     = new Matrix4f();
    @Unique private final Matrix4f prevProjection    = new Matrix4f();
    @Unique private final Matrix4f scratchModelView  = new Matrix4f();
    @Unique private final Matrix4f scratchProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;
    @Unique private boolean previousVelocityStateReady;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void naturalMotionBlur$onRenderHead(
            GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker,
            boolean renderOutline,
            Camera camera,
            Matrix4f modelViewMatrix,
            Matrix4f projectionMatrix,
            Matrix4f cullingMatrix,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci
    ) {
        ConfigEntries config = ConfigManager.getConfig();
        boolean blurActive = config.enabled && config.getEffectiveMotionBlurStrength() != 0.0f;
        boolean recordingActive = config.recordingOverlayEnabled;
        boolean needsVelocityState = blurActive && config.usesVelocityBlur();

        double cx = camera.getPosition().x();
        double cy = camera.getPosition().y();
        double cz = camera.getPosition().z();

        if (!blurActive && !recordingActive) {
            ShaderManager.clearFrameAllocator();
            naturalMotionBlur$updatePreviousVelocityState(modelViewMatrix, projectionMatrix, cx, cy, cz);
            previousVelocityStateReady = false;
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
            naturalMotionBlur$updatePreviousVelocityState(modelViewMatrix, projectionMatrix, cx, cy, cz);
            previousVelocityStateReady = false;
            return;
        }

        float dx = previousVelocityStateReady ? (float)(cx - prevCamX) : 0.0f;
        float dy = previousVelocityStateReady ? (float)(cy - prevCamY) : 0.0f;
        float dz = previousVelocityStateReady ? (float)(cz - prevCamZ) : 0.0f;

        scratchModelView.set(modelViewMatrix);
        scratchProjection.set(projectionMatrix);

        ShaderManager.setFrameMotionBlur(
                scratchModelView, prevModelView,
                scratchProjection, prevProjection,
                dx, dy, dz
        );

        naturalMotionBlur$updatePreviousVelocityState(scratchModelView, scratchProjection, cx, cy, cz);
        previousVelocityStateReady = true;
    }

    // Apply pre-entity blur.
    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void naturalMotionBlur$beforeSubmitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();

        if (!config.enabled || config.getEffectiveMotionBlurStrength() == 0.0f || !config.usesVelocityBlur()) {
            return;
        }
        if (naturalMotionBlur$shouldUseSpecialSingleBlur()) {
            ShaderManager.applyF5EntityRideBlur();
            return;
        }
        ShaderManager.applyPreEntityBlur();
    }

    // Apply post-entity blur.
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void naturalMotionBlur$onRenderLevelTail(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, Camera camera, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Matrix4f cullingMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();
        boolean specialSingleBlur = naturalMotionBlur$shouldUseSpecialSingleBlur();

        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
            if (!specialSingleBlur) {
                ShaderManager.applyPostRenderVelocityOnly();
            }
        } else if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.VELOCITY_BASED && !specialSingleBlur) {
            ShaderManager.applyPostRenderVelocityOnly();
        }
    }

    @Unique
    private void naturalMotionBlur$updatePreviousVelocityState(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, double camX, double camY, double camZ) {
        prevModelView.set(modelViewMatrix);
        prevProjection.set(projectionMatrix);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
    }

    @Unique
    private boolean naturalMotionBlur$shouldUseSpecialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) { return true; }
        return client.player != null && client.player.isPassenger();
    }
}