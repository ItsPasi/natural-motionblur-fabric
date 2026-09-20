package net.natural.motionblur.recording;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.WGL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR;
import org.lwjgl.system.windows.User32;

final class HiddenWglContext {
    private static long window = 0L;
    private static long dc = 0L;
    private static long context = 0L;
    private static GLCapabilities capabilities = null;

    private HiddenWglContext() {}

    static State capture() {
        long currentContext = 0L;
        long currentDc = 0L;
        try {
            currentContext = WGL.wglGetCurrentContext(null);
            currentDc = WGL.wglGetCurrentDC();
        } catch (Throwable ignored) {
        }
        return new State(currentDc, currentContext, currentCapabilitiesOrNull());
    }

    static void makeCurrent() {
        ensureCreated();
        if (!WGL.wglMakeCurrent(null, dc, context)) {
            throw new IllegalStateException("Failed to activate hidden WGL context for Vulkan Spout output");
        }
        GL.setCapabilities(capabilities);
    }

    static void restore(State state) {
        try {
            if (state.context != 0L) {
                WGL.wglMakeCurrent(null, state.dc, state.context);
                GL.setCapabilities(state.capabilities);
            } else {
                WGL.wglMakeCurrent(null, 0L, 0L);
                GL.setCapabilities(null);
            }
        } catch (Throwable ignored) {
        }
    }

    static void destroy() {
        State previous = capture();
        long destroyedContext = context;

        try {
            if (context != 0L && previous.context == context) {
                WGL.wglMakeCurrent(null, 0L, 0L);
                GL.setCapabilities(null);
            }

            if (context != 0L) {
                try {
                    WGL.wglDeleteContext(null, context);
                } catch (Throwable ignored) {
                }
            }
            context = 0L;
            capabilities = null;

            if (dc != 0L && window != 0L) {
                try {
                    User32.ReleaseDC(window, dc);
                } catch (Throwable ignored) {
                }
            }
            dc = 0L;

            if (window != 0L) {
                try {
                    User32.DestroyWindow(null, window);
                } catch (Throwable ignored) {
                }
            }
            window = 0L;
        } finally {
            if (previous.context != 0L && previous.context != destroyedContext) {
                restore(previous);
            }
        }
    }

    private static void ensureCreated() {
        if (context != 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            window = User32.CreateWindowEx(null, 0, "STATIC", "NaturalMotionBlur Spout", User32.WS_POPUP, 0, 0, 1, 1, 0L, 0L, 0L, 0L);
            if (window == 0L) {
                throw new IllegalStateException("Failed to create hidden Win32 window for Vulkan Spout output");
            }

            dc = User32.GetDC(window);
            if (dc == 0L) {
                throw new IllegalStateException("Failed to get hidden Win32 device context for Vulkan Spout output");
            }

            PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.calloc(stack)
                    .nSize((short) PIXELFORMATDESCRIPTOR.SIZEOF)
                    .nVersion((short) 1)
                    .dwFlags(GDI32.PFD_DRAW_TO_WINDOW | GDI32.PFD_SUPPORT_OPENGL | GDI32.PFD_DOUBLEBUFFER)
                    .iPixelType(GDI32.PFD_TYPE_RGBA)
                    .cColorBits((byte) 32)
                    .cDepthBits((byte) 0)
                    .cStencilBits((byte) 0)
                    .iLayerType(GDI32.PFD_MAIN_PLANE);

            int pixelFormat = GDI32.ChoosePixelFormat(null, dc, pfd);
            if (pixelFormat == 0) {
                throw new IllegalStateException("Failed to choose hidden WGL pixel format");
            }
            if (!GDI32.SetPixelFormat(null, dc, pixelFormat, pfd)) {
                throw new IllegalStateException("Failed to set hidden WGL pixel format");
            }

            context = WGL.wglCreateContext(null, dc);
            if (context == 0L) {
                throw new IllegalStateException("Failed to create hidden WGL context for Vulkan Spout output");
            }
            if (!WGL.wglMakeCurrent(null, dc, context)) {
                throw new IllegalStateException("Failed to activate hidden WGL context for Vulkan Spout output");
            }
            capabilities = GL.createCapabilities();
        } catch (Throwable t) {
            try {
                destroy();
            } catch (Throwable ignored) {
            }
            throw t;
        }
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        try {
            return GL.getCapabilities();
        } catch (Throwable ignored) {
            return null;
        }
    }

    record State(long dc, long context, GLCapabilities capabilities) {}
}
