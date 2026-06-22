package net.natural.motionblur.mixin;

import net.minecraft.client.Minecraft;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Capture the final client framebuffer near the end of the client frame
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "runTick", at = @At("TAIL"), require = 0)
    private void nmb$afterRunTick(boolean advanceGameTime, CallbackInfo ci) {
        RecordingShaderManager.captureFinalFrameAndPresent();
    }
}