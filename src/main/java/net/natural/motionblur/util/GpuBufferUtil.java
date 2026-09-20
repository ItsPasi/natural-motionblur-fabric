package net.natural.motionblur.util;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GpuBufferUtil {

    private static final int UBO_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
    private GpuBufferUtil() {}

    public static GpuBuffer createUBO(String debugName, int sizeBytes) {
        Supplier<String> label = () -> "naturalmotionblur:" + debugName;
        return RenderSystem.getDevice().createBuffer(label, UBO_USAGE, sizeBytes);
    }

    public static void writeStd140(GpuBuffer buffer, int sizeBytes, Consumer<Std140Builder> writer) {
        ByteBuffer data = MemoryUtil.memCalloc(sizeBytes);
        try {
            Std140Builder b = Std140Builder.intoBuffer(data);
            writer.accept(b);
            data.position(0);
            data.limit(sizeBytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0L, sizeBytes), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    public static void closeQuietly(GpuBuffer buffer) {
        if (buffer == null) return;
        try {
            buffer.close();
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isNotClosedBufferException(RuntimeException e) {
        String message = e.getMessage();
        return message == null || !message.toLowerCase().contains("closed");
    }
}