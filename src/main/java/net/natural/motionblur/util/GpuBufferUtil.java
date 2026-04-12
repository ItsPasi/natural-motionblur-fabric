package net.natural.motionblur.util;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Shared utility for creating GPU uniform-buffer objects via the reflected
 * GpuDevice.createBuffer() API.  Caches the reflected Method so the lookup
 * only happens once across the whole mod lifetime.
 */
public final class GpuBufferUtil {

    // GL_DYNAMIC_DRAW-compatible usage flags understood by the GpuDevice impl.
    private static final int UBO_USAGE = 130;

    private static Method createBufferMethod = null;

    private GpuBufferUtil() {}

    /**
     * Allocates a GPU buffer sized for a UBO.
     *
     * @param debugName label visible in GPU profilers / debug layers
     * @param sizeBytes exact byte size of the std140 block
     */
    public static GpuBuffer createUBO(String debugName, int sizeBytes) {
        Object device = RenderSystem.getDevice();
        Supplier<String> label = () -> "naturalmotionblur:" + debugName;
        try {
            if (createBufferMethod == null)
                createBufferMethod = device.getClass().getMethod(
                        "createBuffer", Supplier.class, int.class, long.class);
            return (GpuBuffer) createBufferMethod.invoke(device, label, UBO_USAGE, (long) sizeBytes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("[NMB] No compatible createBuffer found on " + device.getClass(), e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[NMB] GpuBufferUtil.createUBO failed", e);
        }
    }
}
