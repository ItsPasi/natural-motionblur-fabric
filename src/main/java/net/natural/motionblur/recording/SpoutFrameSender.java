package net.natural.motionblur.recording;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.function.Supplier;

// Sends the recording render target to Spout
public final class SpoutFrameSender {

    private static final int BYTES_PER_PIXEL = 4;
    private static final int READBACK_RING_SIZE = 3;

    private static boolean warnedCpuFallback = false;
    private static boolean warnedFailure = false;
    private static boolean warnedImageFallback = false;

    private static final ReadbackSlot[] readbackSlots = new ReadbackSlot[READBACK_RING_SIZE];
    private static int nextReadbackSlot = 0;
    private static int nextDrainSlot = 0;

    private static long glWindow = 0L;
    private static GLCapabilities glCapabilities = null;
    private static int spoutTexture = 0;
    private static int spoutTextureWidth = 0;
    private static int spoutTextureHeight = 0;

    private SpoutFrameSender() {}

    public static void send(RenderTarget target, int width, int height) {
        if (target == null || width <= 0 || height <= 0) return;

        GpuTexture texture = target.getColorTexture();
        if (texture == null) return;

        if (isOpenGlBackend()) {
            int glTexId = GpuTextureHelper.getGlId(texture);
            if (glTexId != 0) SpoutBridge.sendTexture(glTexId, width, height);
            return;
        }

        sendVulkanFrame(texture, width, height);
    }

    private static void sendVulkanFrame(GpuTexture texture, int width, int height) {
        if (VulkanGlSharedSpoutSender.send(texture, width, height)) return;

        if (!warnedCpuFallback) {
            warnedCpuFallback = true;
            System.out.println("[NMB] Vulkan Spout is using readback fallback.");
        }

        queueReadback(texture, width, height);
        drainOneReadyReadback();
    }

    private static void queueReadback(GpuTexture texture, int width, int height) {
        int size = width * height * BYTES_PER_PIXEL;
        if (size <= 0) return;

        ReadbackSlot slot = findFreeReadbackSlot();
        if (slot == null) return;

        try {
            ensureReadbackBuffer(slot, size);
            slot.size = size;
            slot.width = width;
            slot.height = height;
            slot.ready = false;
            slot.inFlight = true;

            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    texture, slot.buffer, 0L,
                    () -> {
                        slot.inFlight = false;
                        slot.ready = true;
                    },
                    0, 0, 0, width, height);
        } catch (Throwable t) {
            slot.inFlight = false;
            slot.ready = false;
            warnFailure(t);
        }
    }

    private static ReadbackSlot findFreeReadbackSlot() {
        for (int i = 0; i < READBACK_RING_SIZE; i++) {
            int index = (nextReadbackSlot + i) % READBACK_RING_SIZE;
            ReadbackSlot slot = slot(index);
            if (!slot.inFlight && !slot.ready) {
                nextReadbackSlot = (index + 1) % READBACK_RING_SIZE;
                return slot;
            }
        }
        return null;
    }

    private static void drainOneReadyReadback() {
        for (int i = 0; i < READBACK_RING_SIZE; i++) {
            int index = (nextDrainSlot + i) % READBACK_RING_SIZE;
            ReadbackSlot slot = slot(index);
            if (!slot.ready) continue;

            slot.ready = false;
            nextDrainSlot = (index + 1) % READBACK_RING_SIZE;

            if (slot.size <= 0 || slot.width <= 0 || slot.height <= 0) return;

            try (MappedView view = slot.buffer.map(0L, slot.size, true, false)) {
                ByteBuffer pixels = view.data().duplicate();
                pixels.position(0);
                pixels.limit(slot.size);

                if (!sendImageDirect(pixels, slot.width, slot.height)) {
                    if (!warnedImageFallback) {
                        warnedImageFallback = true;
                        System.err.println("[NMB] Spout SendImage failed; using GL texture fallback.");
                    }
                    uploadAndSendOpenGlTexture(pixels, slot.width, slot.height);
                }
            } catch (Throwable t) {
                warnFailure(t);
            }
            return;
        }
    }

    private static ReadbackSlot slot(int index) {
        ReadbackSlot slot = readbackSlots[index];
        if (slot == null) {
            slot = new ReadbackSlot();
            readbackSlots[index] = slot;
        }
        return slot;
    }

    private static void ensureReadbackBuffer(ReadbackSlot slot, int size) {
        if (slot.buffer != null && slot.bufferSize >= size) return;
        if (slot.buffer != null) {
            slot.buffer.close();
            slot.buffer = null;
        }

        int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
        slot.buffer = RenderSystem.getDevice().createBuffer(
                (Supplier<String>) () -> "naturalmotionblur:spout_vulkan_readback",
                usage,
                (long) size);
        slot.bufferSize = size;
    }


    private static boolean sendImageDirect(ByteBuffer pixels, int width, int height) {
        pixels.position(0);
        pixels.limit(width * height * BYTES_PER_PIXEL);
        return SpoutBridge.sendImage(pixels, width, height, width * BYTES_PER_PIXEL, true);
    }

    private static void uploadAndSendOpenGlTexture(ByteBuffer pixels, int width, int height) {
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = null;
        try {
            previousCapabilities = GL.getCapabilities();
        } catch (Throwable ignored) {
        }

        boolean restorePreviousContext = previousContext != 0L && previousContext != glWindow;
        try {
            ensureOpenGlContext();
            if (previousContext != glWindow) {
                GLFW.glfwMakeContextCurrent(glWindow);
                GL.setCapabilities(glCapabilities);
            }

            ensureOpenGlTexture(width, height, pixels);
            SpoutBridge.sendTexture(spoutTexture, width, height);
        } finally {
            if (restorePreviousContext) {
                GLFW.glfwMakeContextCurrent(previousContext);
                GL.setCapabilities(previousCapabilities);
            }
        }
    }

    private static void ensureOpenGlContext() {
        if (glWindow != 0L) return;

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW is not initialized");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        glWindow = GLFW.glfwCreateWindow(1, 1, "NaturalMotionBlur Spout", 0L, 0L);
        if (glWindow == 0L) {
            throw new IllegalStateException("Failed to create hidden OpenGL context for Vulkan Spout output");
        }

        GLFW.glfwMakeContextCurrent(glWindow);
        glCapabilities = GL.createCapabilities();
    }

    private static void ensureOpenGlTexture(int width, int height, ByteBuffer pixels) {
        if (spoutTexture == 0) {
            spoutTexture = GL11.glGenTextures();
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, spoutTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

        if (spoutTextureWidth != width || spoutTextureHeight != height) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            spoutTextureWidth = width;
            spoutTextureHeight = height;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        }
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

    private static void warnFailure(Throwable t) {
        if (!warnedFailure) {
            warnedFailure = true;
            System.err.println("[NMB] Vulkan Spout fallback failed: " + t);
            t.printStackTrace();
        }
    }

    public static void shutdown() {
        try {
            shutdownReadbackBuffers();
            destroyHiddenOpenGlTextureAndContext();
            warnedCpuFallback = false;
            warnedFailure = false;
            warnedImageFallback = false;
            VulkanGlSharedSpoutSender.destroy();
            SpoutBridge.shutdown();
        } catch (Throwable ignored) {
            shutdownWithoutRenderThreadCleanup();
        }
    }

    public static void shutdownWithoutRenderThreadCleanup() {
        discardReadbackBuffersWithoutClose();
        spoutTexture = 0;
        spoutTextureWidth = 0;
        spoutTextureHeight = 0;
        glWindow = 0L;
        glCapabilities = null;
        warnedCpuFallback = false;
        warnedFailure = false;
        warnedImageFallback = false;
        VulkanGlSharedSpoutSender.discardWithoutRenderThreadCleanup();
        try {
            SpoutBridge.shutdown();
        } catch (Throwable ignored) {
        }
    }

    private static void shutdownReadbackBuffers() {
        for (ReadbackSlot slot : readbackSlots) {
            if (slot != null && slot.buffer != null) {
                try {
                    slot.buffer.close();
                } catch (Throwable ignored) {
                }
                slot.buffer = null;
                slot.bufferSize = 0;
                slot.inFlight = false;
                slot.ready = false;
            }
        }
        nextReadbackSlot = 0;
        nextDrainSlot = 0;
    }

    private static void discardReadbackBuffersWithoutClose() {
        for (ReadbackSlot slot : readbackSlots) {
            if (slot != null) {
                slot.buffer = null;
                slot.bufferSize = 0;
                slot.inFlight = false;
                slot.ready = false;
            }
        }
        nextReadbackSlot = 0;
        nextDrainSlot = 0;
    }

    private static void destroyHiddenOpenGlTextureAndContext() {
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCapabilities = null;
        try {
            previousCapabilities = GL.getCapabilities();
        } catch (Throwable ignored) {
        }

        if (glWindow != 0L) {
            try {
                GLFW.glfwMakeContextCurrent(glWindow);
                GL.setCapabilities(glCapabilities);
                if (spoutTexture != 0) {
                    GL11.glDeleteTextures(spoutTexture);
                    spoutTexture = 0;
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (previousContext != glWindow) {
                        GLFW.glfwMakeContextCurrent(previousContext);
                        GL.setCapabilities(previousCapabilities);
                    } else {
                        GLFW.glfwMakeContextCurrent(0L);
                        GL.setCapabilities(null);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    GLFW.glfwDestroyWindow(glWindow);
                } catch (Throwable ignored) {
                }
                glWindow = 0L;
                glCapabilities = null;
            }
        }

        spoutTextureWidth = 0;
        spoutTextureHeight = 0;
    }

    private static final class ReadbackSlot {
        private GpuBuffer buffer = null;
        private int bufferSize = 0;
        private volatile boolean inFlight = false;
        private volatile boolean ready = false;
        private int size = 0;
        private int width = 0;
        private int height = 0;
    }
}