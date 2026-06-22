package net.natural.motionblur.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.natural.motionblur.mixin.GameRendererAccessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ClientRenderTargets {

    private static Field cachedGameRendererField = null;
    private static Method cachedGameRendererMainRenderTargetMethod = null;
    private static Field cachedMinecraftRenderTargetField = null;
    private static Method cachedMinecraftRenderTargetMethod = null;

    private ClientRenderTargets() {}

    public static RenderTarget getMain(Minecraft client) {
        RenderTarget target = tryGameRenderer(client);
        if (target != null) return target;

        target = tryCachedMinecraftField(client);
        if (target != null) return target;

        target = tryCachedMinecraftMethod(client);
        if (target != null) return target;

        target = findMinecraftRenderTargetField(client);
        if (target != null) return target;

        target = findMinecraftRenderTargetMethod(client);
        if (target != null) return target;

        throw new IllegalStateException("[NMB] Could not access main render target");
    }

    private static RenderTarget tryGameRenderer(Minecraft client) {
        GameRenderer gameRenderer = getGameRenderer(client);
        if (gameRenderer == null) return null;

        RenderTarget target = tryGameRendererInvoker(gameRenderer);
        if (target != null) return target;

        target = tryCachedGameRendererMethod(gameRenderer);
        if (target != null) return target;

        return findGameRendererRenderTargetMethod(gameRenderer);
    }

    private static RenderTarget tryGameRendererInvoker(GameRenderer gameRenderer) {
        try {
            RenderTarget target = ((GameRendererAccessor)gameRenderer).naturalMotionBlur$mainRenderTarget();
            if (isUsable(target)) return target;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static GameRenderer getGameRenderer(Minecraft client) {
        Field field = cachedGameRendererField;
        if (field != null) {
            try {
                Object value = field.get(client);
                if (value instanceof GameRenderer gameRenderer) return gameRenderer;
            } catch (ReflectiveOperationException ignored) {
                cachedGameRendererField = null;
            }
        }

        for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (!GameRenderer.class.isAssignableFrom(candidate.getType())) continue;
                if (Modifier.isStatic(candidate.getModifiers())) continue;

                try {
                    candidate.setAccessible(true);
                    Object value = candidate.get(client);
                    if (value instanceof GameRenderer gameRenderer) {
                        cachedGameRendererField = candidate;
                        return gameRenderer;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static RenderTarget tryCachedGameRendererMethod(GameRenderer gameRenderer) {
        Method method = cachedGameRendererMainRenderTargetMethod;
        if (method == null) return null;

        try {
            Object value = method.invoke(gameRenderer);
            if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) return renderTarget;
        } catch (ReflectiveOperationException ignored) {
            cachedGameRendererMainRenderTargetMethod = null;
        }
        return null;
    }

    private static RenderTarget findGameRendererRenderTargetMethod(GameRenderer gameRenderer) {
        for (Class<?> type = gameRenderer.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                if (!RenderTarget.class.isAssignableFrom(method.getReturnType())) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;

                try {
                    method.setAccessible(true);
                    Object value = method.invoke(gameRenderer);
                    if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) {
                        cachedGameRendererMainRenderTargetMethod = method;
                        return renderTarget;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static RenderTarget tryCachedMinecraftField(Minecraft client) {
        Field field = cachedMinecraftRenderTargetField;
        if (field == null) return null;

        try {
            Object value = field.get(client);
            if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) return renderTarget;
        } catch (ReflectiveOperationException ignored) {
            cachedMinecraftRenderTargetField = null;
        }
        return null;
    }

    private static RenderTarget tryCachedMinecraftMethod(Minecraft client) {
        Method method = cachedMinecraftRenderTargetMethod;
        if (method == null) return null;

        try {
            Object value = method.invoke(client);
            if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) return renderTarget;
        } catch (ReflectiveOperationException ignored) {
            cachedMinecraftRenderTargetMethod = null;
        }
        return null;
    }

    private static RenderTarget findMinecraftRenderTargetField(Minecraft client) {
        for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!RenderTarget.class.isAssignableFrom(field.getType())) continue;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(client);
                    if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) {
                        cachedMinecraftRenderTargetField = field;
                        return renderTarget;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static RenderTarget findMinecraftRenderTargetMethod(Minecraft client) {
        for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                if (!RenderTarget.class.isAssignableFrom(method.getReturnType())) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;

                try {
                    method.setAccessible(true);
                    Object value = method.invoke(client);
                    if (value instanceof RenderTarget renderTarget && isUsable(renderTarget)) {
                        cachedMinecraftRenderTargetMethod = method;
                        return renderTarget;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isUsable(RenderTarget target) {
        return target != null
                && target.width > 0
                && target.height > 0
                && (target.getColorTexture() != null || target.getColorTextureView() != null);
    }
}