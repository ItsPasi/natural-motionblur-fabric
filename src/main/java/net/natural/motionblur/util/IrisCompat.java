package net.natural.motionblur.util;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class IrisCompat {

    private static boolean irisLookupDone;
    private static Method isPackInUseQuick;

    private IrisCompat() {}

    public static boolean isShaderPackInUse() {
        try {
            ensureIrisLookup();
            return isPackInUseQuick != null
                    && Boolean.TRUE.equals(isPackInUseQuick.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getDepthConvention() {
        return isShaderPackInUse() ? 1 : 0;
    }

    public static void copyGbufferMatrices(Matrix4f modelViewOut, Matrix4f projectionOut) {
        if (!isShaderPackInUse()) return;

        try {
            Class<?> stateClass = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Field instanceField = stateClass.getField("INSTANCE");
            Object state = instanceField.get(null);
            Method getModelView = stateClass.getMethod("getGbufferModelView");
            Method getProjection = stateClass.getMethod("getGbufferProjection");
            Object mv = getModelView.invoke(state);
            Object projection = getProjection.invoke(state);
            if (mv instanceof Matrix4fc mvMatrix && projection instanceof Matrix4fc projectionMatrix) {
                modelViewOut.set(mvMatrix);
                projectionOut.set(projectionMatrix);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object gameRenderer = Minecraft.getInstance().gameRenderer;
            Method getProjection = gameRenderer.getClass().getMethod("sodium$getProjectionMatrix");
            Object projection = getProjection.invoke(gameRenderer);
            if (projection instanceof Matrix4fc projectionMatrix) {
                projectionOut.set(projectionMatrix);
            }
        } catch (Throwable ignored) {
        }

    }


    private static void ensureIrisLookup() throws Exception {
        if (irisLookupDone) return;
        irisLookupDone = true;
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            isPackInUseQuick = irisClass.getMethod("isPackInUseQuick");
        } catch (ClassNotFoundException e) {
            isPackInUseQuick = null;
        }
    }
}
