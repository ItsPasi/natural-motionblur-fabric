package net.natural.motionblur.mixin;

import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor List<PostPass.Input> getInputs();
}
