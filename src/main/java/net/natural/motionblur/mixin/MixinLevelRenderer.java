package net.natural.motionblur.mixin;

import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.render.*;
import net.natural.motionblur.MotionBlurMod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class MixinLevelRenderer {

    @Unique private Matrix4f prevModelView = new Matrix4f();
    @Unique private Matrix4f prevProjection = new Matrix4f();
    @Unique private Vector3f prevCameraPos = new Vector3f();

    @Inject(method = "render", at = @At("HEAD"))
    private void setMatrices(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        float tickDelta = tickCounter.getTickDelta(true);
        MotionBlurMod.setFrameMotionBlur(positionMatrix, prevModelView,
                gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)),
                prevProjection,
                new Vector3f(
                        (float) (camera.getPos().x % 30000f),
                        (float) (camera.getPos().y % 30000f),
                        (float) (camera.getPos().z % 30000f)
                ),
                prevCameraPos
        );
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void setOldMatrices(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        prevModelView = new Matrix4f(positionMatrix);
        float tickDelta = tickCounter.getTickDelta(true);
        prevProjection = new Matrix4f(gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)));
        prevCameraPos = new Vector3f(
                (float) (camera.getPos().x % 30000f),
                (float) (camera.getPos().y % 30000f),
                (float) (camera.getPos().z % 30000f)
        );
    }
}
