package net.natural.motionblur.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 1100)
public class MixinGameRenderer {

    @Inject(
            method = "renderWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;render(" +
                            "Lnet/minecraft/client/util/ObjectAllocator;" +
                            "Lnet/minecraft/client/render/RenderTickCounter;" +
                            "ZLnet/minecraft/client/render/Camera;" +
                            "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;" +
                            "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;" +
                            "Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void afterWorldRender(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!ConfigManager.getConfig().enabled) return;
        ShaderManager.applyMotionBlur();
    }
}