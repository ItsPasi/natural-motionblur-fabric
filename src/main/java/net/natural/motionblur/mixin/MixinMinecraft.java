package net.natural.motionblur.mixin;

import net.minecraft.client.Minecraft;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// MC26.1 official mappings:
// Capture after the main framebuffer is blitted to the screen,
// immediately before Minecraft flips/presents the frame.
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void nmb$beforeFlipFrame(boolean advanceGameTime, CallbackInfo ci) {
        RecordingShaderManager.captureFinalFrameAndPresent();
    }
}