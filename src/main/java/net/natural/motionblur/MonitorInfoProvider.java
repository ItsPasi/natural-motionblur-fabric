package net.natural.motionblur;

import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

public class MonitorInfoProvider {

    private static long lastMonitorHandle = 0;
    private static int  lastRefreshRate   = 60;
    private static long lastCheckTime     = 0;
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L; // 1 second

    // Update refresh rate detection
    public static void updateDisplayInfo() {
        // Reduce Update Checking
        long now = System.nanoTime();
        if (now - lastCheckTime < CHECK_INTERVAL_NS) return;
        lastCheckTime = now;

        Minecraft client = Minecraft.getInstance();

        long window = client.getWindow().getWindow();
        long monitor = GLFW.glfwGetWindowMonitor(window);

        // If windowed mode, manually detect monitor from window position
        if (monitor == 0) {
            monitor = getMonitorFromWindowPosition(window, client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight());
        }

        // If monitor changed, update refresh rate
        if (monitor != lastMonitorHandle) {
            lastRefreshRate   = detectRefreshRate(monitor);
            lastMonitorHandle = monitor;
        }
    }

    // Gets the current detected refresh rate.
    public static int getRefreshRate() {
        return lastRefreshRate;
    }

    // Finds which monitor the window centre sits on, falls back to the primary monitor
    private static long getMonitorFromWindowPosition(long window, int windowWidth, int windowHeight) {
        int[] winX = new int[1], winY = new int[1];
        GLFW.glfwGetWindowPos(window, winX, winY);

        int centerX = winX[0] + windowWidth  / 2;
        int centerY = winY[0] + windowHeight / 2;

        long result        = GLFW.glfwGetPrimaryMonitor();
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long m = monitors.get(i);
                int[] mx = new int[1], my = new int[1];
                GLFW.glfwGetMonitorPos(m, mx, my);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
                if (mode == null) continue;
                if (centerX >= mx[0] && centerX < mx[0] + mode.width() &&
                        centerY >= my[0] && centerY < my[0] + mode.height()) {
                    result = m;
                    break;
                }
            }
        }
        return result;
    }

    private static int detectRefreshRate(long monitor) {
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
        return (vidMode != null) ? vidMode.refreshRate() : 60;
    }
}