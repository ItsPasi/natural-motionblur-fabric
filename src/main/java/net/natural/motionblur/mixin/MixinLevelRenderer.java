package net.natural.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 800)
public class MixinLevelRenderer {

    @Unique private final Matrix4f prevModelView     = new Matrix4f();
    @Unique private final Matrix4f prevProjection    = new Matrix4f();
    @Unique private final Matrix4f scratchModelView  = new Matrix4f();
    @Unique private final Matrix4f scratchProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;
    @Unique private boolean previousFrameReady = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(
            GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
            boolean renderOutline, CameraRenderState cameraState,
            Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog,
            Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {

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

    @Inject(method = "submitEntities", at = @At("HEAD"), require = 0)
    private void naturalMotionBlur$beforeSubmitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci) {
        ShaderManager.applyPreEntityVelocityOnly(naturalMotionBlur$shouldUseSpecialSingleBlur());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void naturalMotionBlur$onRenderLevelTail(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
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
    private boolean naturalMotionBlur$shouldUseSpecialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) return true;
        return client.player != null && client.player.isPassenger();
    }
}