package net.natural.motionblur.recording;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

// Extracts the raw OpenGL texture integer ID from a GpuTexture.
public class GpuTextureHelper {

    private static Method  cachedMethod = null;
    private static Field   cachedField  = null;
    private static boolean resolved     = false;
    private static boolean failed       = false;
    private static boolean warnedBackend = false;

    public static int getGlId(GpuTexture texture) {
        if (!isOpenGlBackend()) {
            if (!warnedBackend) {
                warnedBackend = true;
                System.err.println("[NMB] Raw GL Spout texture output is only available on the OpenGL renderer.");
            }
            return 0;
        }
        if (failed) return 0;
        if (!resolved) resolve(texture);

        try {
            if (cachedMethod != null) return (int) cachedMethod.invoke(texture);
            if (cachedField  != null) return (int) cachedField.get(texture);
        } catch (Exception e) {
            System.err.println("[NMB] GpuTextureHelper: failed to get GL id: " + e);
            failed = true;
        }
        return 0;
    }


    private static boolean isOpenGlBackend() {
        try {
            Object device = RenderSystem.getDevice();
            String name = device.getClass().getName().toLowerCase(Locale.ROOT);
            Object backend = findBackend(device);
            if (backend != null) name += " " + backend.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("vulkan")) return false;
            return name.contains("opengl") || name.contains("gl");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findBackend(Object device) {
        for (Class<?> c = device.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("backend");
                field.setAccessible(true);
                return field.get(device);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void resolve(GpuTexture texture) {
        resolved = true;
        Class<?> cls = texture.getClass();

        // Strategy 1 & 2: look for a method returning int
        for (String name : new String[]{"getNativeHandle", "getHandle", "getId", "getTextureId", "getGlId"}) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    // Verify it returns int
                    if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                        cachedMethod = m;
                        return;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        }

        // Strategy 3: find an int field whose name looks like a GL handle
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                String n = f.getName().toLowerCase();
                if (n.contains("id") || n.contains("handle") || n.contains("texture") || n.contains("name")) {
                    f.setAccessible(true);
                    // Quick sanity check: value should be > 0 for a valid GL texture
                    try {
                        int val = (int) f.get(texture);
                        if (val > 0) {
                            cachedField = f;
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        System.err.println("[NMB] GpuTextureHelper: could not find GL texture id on " + cls.getName()
                + ". Fields: " + listFields(cls));
        failed = true;
    }

    private static String listFields(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                sb.append(f.getType().getSimpleName()).append(" ").append(f.getName()).append(", ");
            }
        }
        return sb.toString();
    }
}