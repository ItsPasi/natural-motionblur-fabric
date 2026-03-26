package net.natural.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.natural.motionblur.ShaderManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Unique private final Matrix4f prevModelView  = new Matrix4f();
    @Unique private final Matrix4f prevProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderHead(
            GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
            boolean renderOutline, CameraRenderState cameraState,
            Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog,
            Vector4f fogColor, boolean shouldRenderSky,
            ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {

        ShaderManager.captureAllocator(resourceAllocator);

        double cx = cameraState.pos.x();
        double cy = cameraState.pos.y();
        double cz = cameraState.pos.z();

        float dx = (float)(cx - prevCamX);
        float dy = (float)(cy - prevCamY);
        float dz = (float)(cz - prevCamZ);

        Matrix4f modelView = new Matrix4f(modelViewMatrix);
        Matrix4f projection = new Matrix4f(cameraState.projectionMatrix);

        ShaderManager.setFrameMotionBlur(
                modelView,      prevModelView,
                projection,     prevProjection,
                dx, dy, dz);

        prevModelView.set(modelViewMatrix);
        prevProjection.set(cameraState.projectionMatrix);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
    }
}