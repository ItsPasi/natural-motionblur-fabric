package net.natural.motionblur.mixin;

import net.minecraft.client.gl.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderLoader.class)
public interface ShaderLoaderAccessor {
    @Accessor ShaderLoader.Cache getCache();
}