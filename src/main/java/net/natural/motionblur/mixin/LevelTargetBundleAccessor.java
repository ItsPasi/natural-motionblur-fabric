package net.natural.motionblur.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelTargetBundle.class)
public interface LevelTargetBundleAccessor {

    @Accessor("main")
    ResourceHandle<RenderTarget> naturalMotionBlur$getMain();

    @Accessor("main")
    void naturalMotionBlur$setMain(ResourceHandle<RenderTarget> target);

    @Accessor("translucent")
    ResourceHandle<RenderTarget> naturalMotionBlur$getTranslucent();

    @Accessor("translucent")
    void naturalMotionBlur$setTranslucent(ResourceHandle<RenderTarget> target);

    @Accessor("itemEntity")
    ResourceHandle<RenderTarget> naturalMotionBlur$getItemEntity();

    @Accessor("itemEntity")
    void naturalMotionBlur$setItemEntity(ResourceHandle<RenderTarget> target);

    @Accessor("weather")
    ResourceHandle<RenderTarget> naturalMotionBlur$getWeather();

    @Accessor("weather")
    void naturalMotionBlur$setWeather(ResourceHandle<RenderTarget> target);

    @Accessor("particles")
    ResourceHandle<RenderTarget> naturalMotionBlur$getParticles();

    @Accessor("particles")
    void naturalMotionBlur$setParticles(ResourceHandle<RenderTarget> target);

    @Accessor("entityOutline")
    ResourceHandle<RenderTarget> naturalMotionBlur$getEntityOutline();

    @Accessor("entityOutline")
    void naturalMotionBlur$setEntityOutline(ResourceHandle<RenderTarget> target);
}