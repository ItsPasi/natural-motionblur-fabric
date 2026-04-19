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
import net.natural.motionblur.recording.RecordingShaderManager;
import net.natural.motionblur.config.ConfigEntries;
import net.natural.motionblur.config.ConfigManager;
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

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void naturalMotionBlur$onRenderLevelHead(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, Camera camera, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Matrix4f frustumMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {

        ShaderManager.captureAllocator(resourceAllocator);
        RecordingShaderManager.captureAllocator(resourceAllocator);
        ShaderManager.beginFrame();

        var camPos = camera.position();
        double cx = camPos.x();
        double cy = camPos.y();
        double cz = camPos.z();

        float dx = (float)(cx - prevCamX);
        float dy = (float)(cy - prevCamY);
        float dz = (float)(cz - prevCamZ);

        scratchModelView.set(modelViewMatrix);
        scratchProjection.set(projectionMatrix);

        ShaderManager.setFrameMotionBlur(
                scratchModelView, prevModelView,
                scratchProjection, prevProjection,
                dx, dy, dz
        );

        prevModelView.set(scratchModelView);
        prevProjection.set(scratchProjection);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
    }

    // Apply pre-entity blur.
    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void naturalMotionBlur$beforeSubmitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();
        if (config.blurAlgorithm == ConfigEntries.BlurAlgorithm.VELOCITY_BASED) {
            if (naturalMotionBlur$shouldUseSpecialSingleBlur()) {
                ShaderManager.applyF5EntityRideBlur();
            } else {
                ShaderManager.applyPreEntityBlur();
            }
        }
    }

    // Apply post-render blur
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void naturalMotionBlur$onRenderLevelTail(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, Camera camera, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Matrix4f frustumMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        ConfigEntries config = ConfigManager.getConfig();
        if (config.blurAlgorithm != ConfigEntries.BlurAlgorithm.VELOCITY_BASED
                || !naturalMotionBlur$shouldUseSpecialSingleBlur()) {
            ShaderManager.applyPostRenderBlur();
        }
        ShaderManager.clearFrameAllocator();
    }

    @Unique
    private boolean naturalMotionBlur$shouldUseSpecialSingleBlur() {
        Minecraft client = Minecraft.getInstance();
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) return true;
        return client.player != null && client.player.isPassenger();
    }
}