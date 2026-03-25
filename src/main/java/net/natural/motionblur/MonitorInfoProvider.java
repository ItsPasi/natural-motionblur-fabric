package net.natural.motionblur;

import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

public class MonitorInfoProvider {

    private static long lastMonitorHandle = 0;
    private static int lastRefreshRate = 60;
    private static long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L; // 1 second

    // Update refresh rate detection
    public static void updateDisplayInfo() {
        // Reduce Update Checking
        long now = System.nanoTime();
        if (now - lastCheckTime < CHECK_INTERVAL_NS) {
            return; // Too soon, skip
        }
        lastCheckTime = now;

        Minecraft client = Minecraft.getInstance();

        long window = client.getWindow().handle();
        long monitor = GLFW.glfwGetWindowMonitor(window);

        // If windowed mode, manually detect monitor from window position
        if (monitor == 0) {
            monitor = getMonitorFromWindowPosition(window, client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight());
        }

        // If monitor changed, update refresh rate
        if (monitor != lastMonitorHandle) {
            lastRefreshRate = detectRefreshRateFromMonitor(monitor);
            lastMonitorHandle = monitor;
        }
    }

    // Gets the current detected refresh rate.
    public static int getRefreshRate() {
        return lastRefreshRate;
    }

    // ---------------- Internal helpers ----------------

    private static long getMonitorFromWindowPosition(long window, int windowWidth, int windowHeight) {
        int[] winX = new int[1];
        int[] winY = new int[1];
        GLFW.glfwGetWindowPos(window, winX, winY);

        int windowCenterX = winX[0] + windowWidth / 2;
        int windowCenterY = winY[0] + windowHeight / 2;

        long monitorResult = GLFW.glfwGetPrimaryMonitor(); // fallback
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long m = monitors.get(i);
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos(m, mx, my);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
                if (mode == null) continue;

                int mw = mode.width();
                int mh = mode.height();

                if (windowCenterX >= mx[0] && windowCenterX < mx[0] + mw &&
                        windowCenterY >= my[0] && windowCenterY < my[0] + mh) {
                    monitorResult = m;
                    break;
                }
            }
        }
        return monitorResult;
    }

    private static int detectRefreshRateFromMonitor(long monitor) {
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
        return (vidMode != null) ? vidMode.refreshRate() : 60;
    }
}
