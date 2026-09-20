package net.natural.motionblur.util;

import java.lang.reflect.Method;

public final class IrisCompat {

    private static boolean apiLookupDone;
    private static Method apiGetInstance;
    private static Method apiIsShaderPackInUse;

    private IrisCompat() {}

    public static boolean isShaderPackInUse() {
        try {
            ensureApiLookup();
            if (apiGetInstance == null || apiIsShaderPackInUse == null) return false;
            Object api = apiGetInstance.invoke(null);
            return Boolean.TRUE.equals(apiIsShaderPackInUse.invoke(api));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureApiLookup() {
        if (apiLookupDone) return;
        apiLookupDone = true;

        try {
            Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            apiGetInstance = irisApi.getMethod("getInstance");
            apiIsShaderPackInUse = irisApi.getMethod("isShaderPackInUse");
        } catch (Throwable ignored) {
            apiGetInstance = null;
            apiIsShaderPackInUse = null;
        }
    }
}
