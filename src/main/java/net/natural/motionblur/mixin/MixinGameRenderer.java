package net.natural.motionblur.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.natural.motionblur.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void naturalMotionBlur$afterRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        ShaderManager.applyDeferredTemporalBlur();
        ShaderManager.clearFrameAllocator();
    }
}
