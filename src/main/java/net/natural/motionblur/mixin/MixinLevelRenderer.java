package net.natural.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import net.natural.motionblur.ShaderManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class MixinLevelRenderer {

    @Unique private final Matrix4f prevModelView  = new Matrix4f();
    @Unique private final Matrix4f prevProjection = new Matrix4f();
    @Unique private double prevCamX, prevCamY, prevCamZ;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(
            ObjectAllocator allocator, RenderTickCounter tickCounter,
            boolean renderBlockOutline, Camera camera,
            Matrix4f positionMatrix, Matrix4f basicProjectionMatrix,
            Matrix4f projectionMatrix, GpuBufferSlice fogBuffer,
            Vector4f fogColor, boolean renderSky, CallbackInfo ci) {

        double cx = camera.getCameraPos().x;
        double cy = camera.getCameraPos().y;
        double cz = camera.getCameraPos().z;

        float dx = (float)(cx - prevCamX);
        float dy = (float)(cy - prevCamY);
        float dz = (float)(cz - prevCamZ);

        ShaderManager.setFrameMotionBlur(
                positionMatrix,        prevModelView,
                basicProjectionMatrix, prevProjection,
                dx, dy, dz);

        prevModelView.set(positionMatrix);
        prevProjection.set(basicProjectionMatrix);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
    }
}