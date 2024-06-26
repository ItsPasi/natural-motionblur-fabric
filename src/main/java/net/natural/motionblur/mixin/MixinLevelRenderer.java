package net.natural.motionblur.mixin;

import net.natural.motionblur.MotionBlurMod;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
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
    private void setMatrices(float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        MotionBlurMod.setFrameMotionBlur(matrix4f, prevModelView, gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)), prevProjection, new Vector3f((float) (camera.getPos().x % 30000f), (float) (camera.getPos().y % 30000f), (float) (camera.getPos().z % 30000f)), prevCameraPos);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void setOldMatrices(float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        prevModelView = new Matrix4f(matrix4f);
        prevProjection = new Matrix4f(gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)));
        prevCameraPos = new Vector3f((float) (camera.getPos().x % 30000f), (float) (camera.getPos().y % 30000f), (float) (camera.getPos().z % 30000f));

    }
}
