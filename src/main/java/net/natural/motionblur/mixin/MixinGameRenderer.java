// TODO(Ravel): Failed to fully resolve file: class com.intellij.psi.impl.source.tree.java.PsiPolyadicExpressionImpl cannot be cast to class com.intellij.psi.PsiLiteralExpression (com.intellij.psi.impl.source.tree.java.PsiPolyadicExpressionImpl and com.intellij.psi.PsiLiteralExpression are in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @514f90a8)
// TODO(Ravel): Failed to fully resolve file: class com.intellij.psi.impl.source.tree.java.PsiPolyadicExpressionImpl cannot be cast to class com.intellij.psi.PsiLiteralExpression (com.intellij.psi.impl.source.tree.java.PsiPolyadicExpressionImpl and com.intellij.psi.PsiLiteralExpression are in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @514f90a8)
package net.natural.motionblur.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.natural.motionblur.ShaderManager;
import net.natural.motionblur.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 1100)
public class MixinGameRenderer {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(" +
                            "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
                            "Lnet/minecraft/client/DeltaTracker;" +
                            "Z" +
                            "Lnet/minecraft/client/renderer/state/level/CameraRenderState;" +
                            "Lorg/joml/Matrix4fc;" +
                            "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;" +
                            "Lorg/joml/Vector4f;" +
                            "Z" +
                            "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;" +
                            ")V",
                    shift = At.Shift.AFTER
            )
    )
    private void afterWorldRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!ConfigManager.getConfig().enabled) return;
        ShaderManager.applyMotionBlur();
    }
}