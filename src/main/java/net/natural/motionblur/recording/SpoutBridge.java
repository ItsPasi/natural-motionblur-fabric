package net.natural.motionblur.recording;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class SpoutBridge {

    private static final String BRIDGE_LIBRARY_NAME = "nmb_spout_bridge.dll";
    private static final String SPOUT_LIBRARY_NAME = "SpoutLibrary.dll";
    private static final String SPOUT_RESOURCE_PATH = "/natives/win64/" + SPOUT_LIBRARY_NAME;
    private static final String BRIDGE_RESOURCE_PATH = "/natives/win64/" + BRIDGE_LIBRARY_NAME;
    private static final String SENDER_NAME = "NaturalMotionBlur";

    private static boolean loadAttempted = false;
    private static boolean loaded = false;
    private static boolean senderOpen = false;
    private static boolean warnedUnsupportedPlatform = false;

    private static int senderWidth = 0;
    private static int senderHeight = 0;

    private SpoutBridge() {}

    public static void sendTexture(int textureId, int width, int height) {
        if (textureId == 0 || width <= 0 || height <= 0) return;
        if (!ensureLoaded()) return;

        if (!senderOpen) {
            if (!nCreateSender(SENDER_NAME, width, height)) {
                System.err.println("[NMB] Failed to create Spout sender.");
                return;
            }
            senderOpen = true;
            senderWidth = width;
            senderHeight = height;
            System.out.println("[NMB] Spout sender ready: " + SENDER_NAME + " (" + width + "x" + height + ")");
        } else if (senderWidth != width || senderHeight != height) {
            if (!nUpdateSender(SENDER_NAME, width, height)) {
                System.err.println("[NMB] Failed to resize Spout sender. Recreating it.");
                nReleaseSender();
                senderOpen = false;
                if (!nCreateSender(SENDER_NAME, width, height)) {
                    System.err.println("[NMB] Failed to recreate Spout sender after resize.");
                    return;
                }
                senderOpen = true;
            }
            senderWidth = width;
            senderHeight = height;
        }

        nSendTexture(textureId, GL11.GL_TEXTURE_2D, width, height, true);
    }

    public static void shutdown() {
        if (!loadAttempted || !loaded) {
            senderOpen = false;
            senderWidth = 0;
            senderHeight = 0;
            return;
        }

        try {
            nReleaseSender();
        } catch (UnsatisfiedLinkError ignored) {
            loaded = false;
        } finally {
            senderOpen = false;
            senderWidth = 0;
            senderHeight = 0;
        }
    }

    private static boolean ensureLoaded() {
        if (!isWindows()) {
            if (!warnedUnsupportedPlatform) {
                warnedUnsupportedPlatform = true;
                System.err.println("[NMB] Spout output is only supported on Windows.");
            }
            return false;
        }

        if (loadAttempted) return loaded;
        loadAttempted = true;

        Path nativeDir = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("naturalmotionblur")
                .resolve("natives");

        try {
            Files.createDirectories(nativeDir);

            Path spoutDll = nativeDir.resolve(SPOUT_LIBRARY_NAME);
            Path bridgeDll = nativeDir.resolve(BRIDGE_LIBRARY_NAME);

            extractBundledResource(SPOUT_RESOURCE_PATH, spoutDll);
            extractBundledResource(BRIDGE_RESOURCE_PATH, bridgeDll);

            System.load(spoutDll.toAbsolutePath().toString());
            System.load(bridgeDll.toAbsolutePath().toString());

            loaded = true;
            System.out.println("[NMB] Spout bridge loaded from " + nativeDir.toAbsolutePath());
            return true;
        } catch (Throwable e) {
            System.err.println("[NMB] Failed to load Spout bridge from bundled natives in "
                    + nativeDir.toAbsolutePath() + ": " + e.getMessage());
            loaded = false;
            return false;
        }
    }

    private static void extractBundledResource(String resourcePath, Path outputPath) throws IOException {
        try (InputStream in = SpoutBridge.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Bundled native not found inside jar: " + resourcePath);
            }
            Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static native boolean nCreateSender(String senderName, int width, int height);
    private static native boolean nUpdateSender(String senderName, int width, int height);
    private static native boolean nSendTexture(int textureId, int textureTarget, int width, int height, boolean invert);
    private static native void nReleaseSender();
}