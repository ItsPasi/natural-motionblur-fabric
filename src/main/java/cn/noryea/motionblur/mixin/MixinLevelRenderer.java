package cn.noryea.motionblur.mixin;

import cn.noryea.motionblur.MotionBlurMod;
import ladysnake.satin.api.experimental.ReadableDepthFramebuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3d;
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
    private void setMatrices(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        MotionBlurMod.setFrameMotionBlur(matrices.peek().getPositionMatrix(), prevModelView, gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)), prevProjection, new Vector3f((float) (camera.getPos().x % 30000f), (float) (camera.getPos().y % 30000f), (float) (camera.getPos().z % 30000f)), prevCameraPos);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void setOldMatrices(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix, CallbackInfo ci) {
        prevModelView = new Matrix4f(matrices.peek().getPositionMatrix());
        prevProjection = new Matrix4f(gameRenderer.getBasicProjectionMatrix(((GameRendererInvoker) gameRenderer).invokeGetFov(camera, tickDelta, true)));
        prevCameraPos = new Vector3f((float) (camera.getPos().x % 30000f), (float) (camera.getPos().y % 30000f), (float) (camera.getPos().z % 30000f));

    }
}
