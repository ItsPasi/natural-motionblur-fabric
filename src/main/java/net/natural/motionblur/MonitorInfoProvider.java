package net.natural.motionblur;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

public class MonitorInfoProvider {

    private static int lastDisplayId = 0;
    private static int lastRefreshRate = 60;
    private static long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;

    public static void updateDisplayInfo() {
        long now = System.nanoTime();
        if (now - lastCheckTime < CHECK_INTERVAL_NS) return;
        lastCheckTime = now;

        try {
            long window = Minecraft.getInstance().getWindow().handle();
            int displayId = SDLVideo.SDL_GetDisplayForWindow(window);
            if (displayId == 0) displayId = SDLVideo.SDL_GetPrimaryDisplay();

            if (displayId != 0 && (displayId != lastDisplayId || lastRefreshRate <= 0)) {
                lastRefreshRate = detectRefreshRate(displayId);
                lastDisplayId = displayId;
            }
        } catch (Throwable ignored) {
            if (lastRefreshRate <= 0) lastRefreshRate = 60;
        }
    }

    public static int getRefreshRate() {
        return lastRefreshRate;
    }

    private static int detectRefreshRate(int displayId) {
        SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(displayId);
        if (mode == null) return 60;
        int refresh = Math.round(mode.refresh_rate());
        return refresh > 0 ? refresh : 60;
    }
}