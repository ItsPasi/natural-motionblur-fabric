package net.natural.motionblur.recording;

import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

//Extracts the raw OpenGL texture integer ID from a GpuTexture
public class GpuTextureHelper {

    private static Method  cachedMethod = null;
    private static Field   cachedField  = null;
    private static boolean resolved     = false;
    private static boolean failed       = false;

    public static int getGlId(GpuTexture texture) {
        if (texture == null || failed) return 0;
        if (!resolved) resolve(texture);

        try {
            if (cachedMethod != null) return (int) cachedMethod.invoke(texture);
            if (cachedField  != null) return (int) cachedField.get(texture);
        } catch (Exception e) {
            System.err.println("[NaturalMotionBlur] GpuTextureHelper: failed to get GL id: " + e);
            failed = true;
        }
        return 0;
    }

    private static void resolve(GpuTexture texture) {
        resolved = true;
        Class<?> cls = texture.getClass();

        // Strategy 1: known method names
        for (String name : new String[]{"getNativeHandle", "getHandle", "getId", "getTextureId", "getGlId"}) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                        int val = (int) m.invoke(texture);
                        if (val > 0) { cachedMethod = m; return; }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Strategy 2: try ALL zero-param methods returning int
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class && m.getReturnType() != Integer.class) continue;
                m.setAccessible(true);
                try {
                    int val = (int) m.invoke(texture);
                    if (val > 0) { cachedMethod = m; return; }
                } catch (Exception ignored) {}
            }
        }

        // Strategy 3: named int fields
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                try {
                    int val = f.getInt(texture);
                    String n = f.getName().toLowerCase();
                    if (val > 0 && (n.contains("id") || n.contains("handle") || n.contains("texture") || n.contains("name"))) {
                        cachedField = f; return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Strategy 4: any positive int field as last resort
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                try {
                    int val = f.getInt(texture);
                    if (val > 0) { cachedField = f; return; }
                } catch (Exception ignored) {}
            }
        }

        System.err.println("[NaturalMotionBlur] GpuTextureHelper: could not find GL texture id on " + cls.getName());
        failed = true;
    }
}