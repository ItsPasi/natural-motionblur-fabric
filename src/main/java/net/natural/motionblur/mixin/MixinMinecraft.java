package net.natural.motionblur.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.natural.motionblur.recording.RecordingShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Runs the recording overlay after the full frame is complete
@Mixin(GameRenderer.class)
public class MixinMinecraft {

    @Inject(method = "render", at = @At("TAIL"))
    private void nmb$onRenderTail(CallbackInfo ci) {RecordingShaderManager.captureFinalFrameAndPresent();}
}