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
    private static boolean warnedMissingImageNative = false;
    private static boolean warnedLegacyBridge = false;
    private static boolean modernBridge = false;

    private static int senderWidth = 0;
    private static int senderHeight = 0;

    private SpoutBridge() {}

    public static void sendTexture(int textureId, int width, int height) {
        if (textureId == 0 || width <= 0 || height <= 0) return;
        if (!ensureLoaded()) return;
        if (!ensureSender(width, height)) return;

        nSendTexture(textureId, GL11.GL_TEXTURE_2D, width, height, true);
    }

    public static boolean sendImage(java.nio.ByteBuffer pixels, int width, int height, int pitch, boolean invert) {
        if (pixels == null || width <= 0 || height <= 0 || pitch <= 0) return false;
        if (!ensureLoaded()) return false;
        if (!ensureSender(width, height)) return false;

        try {
            return nSendImage(pixels, width, height, pitch, invert);
        } catch (UnsatisfiedLinkError e) {
            if (!warnedMissingImageNative) {
                warnedMissingImageNative = true;
                System.err.println("[NMB] Spout bridge is missing nSendImage; using fallback output.");
            }
            return false;
        }
    }

    public static boolean isAvailable() {
        return ensureLoaded();
    }

    private static boolean ensureSender(int width, int height) {
        if (!senderOpen) {
            if (!nCreateSender(SENDER_NAME, width, height)) {
                System.err.println("[NMB] Failed to create Spout sender.");
                return false;
            }
            senderOpen = true;
            senderWidth = width;
            senderHeight = height;
            System.out.println("[NMB] Spout sender ready: " + SENDER_NAME + " (" + width + "x" + height + ")");
        } else if (senderWidth != width || senderHeight != height) {
            if (!modernBridge) {
                // Older bridge DLLs resize implicitly from SendTexture.
                senderWidth = width;
                senderHeight = height;
                if (!warnedLegacyBridge) {
                    warnedLegacyBridge = true;
                    System.err.println("[NMB] Old Spout bridge detected; using fallback resize behavior.");
                }
                return true;
            }

            nReleaseSender();
            senderOpen = false;
            senderWidth = 0;
            senderHeight = 0;
            if (!nCreateSender(SENDER_NAME, width, height)) {
                System.err.println("[NMB] Failed to recreate Spout sender after resize.");
                return false;
            }
            senderOpen = true;
            senderWidth = width;
            senderHeight = height;
            System.out.println("[NMB] Spout sender recreated after resize: " + SENDER_NAME + " (" + width + "x" + height + ")");
        }
        return true;
    }

    public static void shutdown() {
        if (!loadAttempted || !loaded) {
            senderOpen = false;
            senderWidth = 0;
            senderHeight = 0;
            return;
        }

        // Runtime stop only pauses output. Releasing Spout here is unstable on some systems.
        senderOpen = false;
        senderWidth = 0;
        senderHeight = 0;
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

        int bridgeVersion = 0;
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
            try {
                bridgeVersion = nBridgeVersion();
                modernBridge = bridgeVersion >= 37;
                System.out.println("[NMB] Spout bridge native version: " + bridgeVersion);
                if (!modernBridge) {
                    System.err.println("[NMB] Spout bridge DLL is too old; Spout output is disabled.");
                    loaded = false;
                    return false;
                }
            } catch (UnsatisfiedLinkError e) {
                bridgeVersion = 0;
                modernBridge = false;
                loaded = false;
                System.err.println("[NMB] Spout bridge DLL is missing required exports. Rebuild nmb_spout_bridge.dll before packaging.");
                return false;
            }
            return true;
        } catch (Throwable e) {
            System.err.println("[NMB] Failed to load Spout bridge from bundled natives in "
                    + nativeDir.toAbsolutePath() + ": " + e.getMessage());
            loaded = false;
            modernBridge = false;
            bridgeVersion = 0;
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
    private static native boolean nSendTexture(int textureId, int textureTarget, int width, int height, boolean invert);
    private static native boolean nSendImage(java.nio.ByteBuffer pixels, int width, int height, int pitch, boolean invert);
    private static native int nBridgeVersion();
    private static native void nReleaseSender();
}