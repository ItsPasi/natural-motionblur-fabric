package net.natural.motionblur.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setLenient().setPrettyPrinting().create();
    private static final List<String> errorMessages = new ArrayList<>();
    private static boolean configReset = false;
    private static boolean delayMessageSent = false;
    private static int tickCounter = 0;
    private static final int TICK_DELAY = 80;

    private static MotionBlurConfig config;

    public static MotionBlurConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    public static void loadConfig() {
        File configFile = getConfigFile();
        boolean configModified = false;
        errorMessages.clear();

        if (!configFile.exists()) {
            config = new MotionBlurConfig();
            saveConfig();
        } else {
            try {
                JsonObject configJson = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), JsonObject.class);
                config = new MotionBlurConfig();

                // Process motionBlurStrength
                if (configJson.has("motionBlurStrength")) {
                    try {
                        float strength = configJson.get("motionBlurStrength").getAsFloat();
                        if (strength < -1000.0F || strength > 1000.0F) {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.motionBlurStrength = 1.0F;
                        errorMessages.add("Strength value of mod \"Natural Motion Blur\" was invalid and has been reset to default (1.0).");
                        configModified = true;
                    }
                }

                // Process motionBlurSamples
                if (configJson.has("motionBlurSamples")) {
                    try {
                        int samples = configJson.get("motionBlurSamples").getAsInt();
                        if (samples < 0 || samples > 1000) {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.motionBlurSamples = 20;
                        errorMessages.add("Sample amount of mod \"Natural Motion Blur\" was invalid and has been reset to default (20).");
                        configModified = true;
                    }
                }

                // Process blurAlgorithm
                if (configJson.has("blurAlgorithm")) {
                    try {
                        config.blurAlgorithm = MotionBlurConfig.BlurAlgorithm.valueOf(configJson.get("blurAlgorithm").getAsString().toUpperCase());
                    } catch (Exception e) {
                        config.blurAlgorithm = MotionBlurConfig.BlurAlgorithm.CENTERED;
                        errorMessages.add("Blur algorithm of mod \"Natural Motion Blur\" was invalid and has been reset to default (CENTERED).");
                        configModified = true;
                    }
                }

                // Process toggleKey
                if (configJson.has("toggleKey")) {
                    try {
                        String key = configJson.get("toggleKey").getAsString();
                        InputUtil.Key parsedKey = InputUtil.fromTranslationKey(key);

                        if (parsedKey == null || key.trim().isEmpty()) {
                            throw new IllegalArgumentException();
                        }
                        config.setToggleKey(parsedKey);
                    } catch (Exception e) {
                        config.setToggleKey(InputUtil.fromTranslationKey("key.keyboard.v"));
                        errorMessages.add("Toggle key of mod \"Natural Motion Blur\" was invalid and has been reset to default (V).");
                        configModified = true;
                    }
                }

                // Process renderF5
                if (configJson.has("renderF5")) {
                    try {
                        String renderF5Value = configJson.get("renderF5").getAsString();
                        if ("true".equalsIgnoreCase(renderF5Value) || "false".equalsIgnoreCase(renderF5Value)) {
                            config.renderF5 = Boolean.parseBoolean(renderF5Value);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.renderF5 = true;
                        errorMessages.add("Third person rendering option of mod \"Natural Motion Blur\" was invalid and has been reset to default (enabled).");
                        configModified = true;
                    }
                }

                // Process enabled
                if (configJson.has("enabled")) {
                    try {
                        String enabledValue = configJson.get("enabled").getAsString();
                        if ("true".equalsIgnoreCase(enabledValue) || "false".equalsIgnoreCase(enabledValue)) {
                            config.enabled = Boolean.parseBoolean(enabledValue);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.enabled = true;
                        errorMessages.add("Toggle option of mod \"Natural Motion Blur\" was invalid and has been reset to default (enabled).");
                        configModified = true;
                    }
                }
            } catch (Exception e) {
                config = new MotionBlurConfig();
                saveConfig();
                configReset = true;
                errorMessages.add("Config file of mod \"Natural Motion Blur\" could not be loaded correctly and has been reset to default.");
                return;
            }

            if (configModified) {
                saveConfig();
                configReset = true;
            }
        }
    }

    public static void saveConfig() {
        File configFile = getConfigFile();
        try {
            FileUtils.write(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("naturalmotionblur.json").toFile();
    }

    public static boolean isConfigReset() {
        return configReset;
    }

    public static void displayErrorMessages(MinecraftClient client) {
        if (configReset && !delayMessageSent && client.player != null) {
            for (String errorMessage : errorMessages) {
                client.player.sendMessage(Text.literal("§c" + errorMessage), false);
            }
            delayMessageSent = true;
            errorMessages.clear();
        }
    }

    public static void incrementTickCounter() {
        tickCounter++;
    }

    public static int getTickCounter() {
        return tickCounter;
    }

    public static int getTickDelay() {
        return TICK_DELAY;
    }

    public static boolean isDelayMessageSent() {
        return delayMessageSent;
    }

    public static void setDelayMessageSent(boolean sent) {
        delayMessageSent = sent;
    }

    public static void resetTickCounter() {
        tickCounter = 0;
    }
}