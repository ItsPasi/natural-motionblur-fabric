package net.natural.motionblur.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import net.natural.motionblur.ShaderManager;
import org.joml.Matrix4f;
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
            GameRenderer gameRenderer,
            Matrix4f positionMatrix, Matrix4f projectionMatrix,
            CallbackInfo ci) {

        ShaderManager.captureAllocator(allocator);

        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;

        ShaderManager.setFrameMotionBlur(
                positionMatrix,   prevModelView,
                projectionMatrix, prevProjection,
                (float)(cx - prevCamX),
                (float)(cy - prevCamY),
                (float)(cz - prevCamZ));

        prevModelView.set(positionMatrix);
        prevProjection.set(projectionMatrix);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
    }
}