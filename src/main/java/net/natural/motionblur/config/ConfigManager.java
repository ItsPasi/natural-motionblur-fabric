package net.natural.motionblur.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import me.shedaniel.clothconfig2.api.*;
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

    private static MotionBlurConfig config;

    public static MotionBlurConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    //Config Screen Interface
    public static void openConfigScreen() {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(null)
                .setTitle(Text.literal("Natural Motion Blur"));

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("Motion Blur Options"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        MotionBlurConfig cfg = getConfig();

        // Toggle Motion Blur
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Toggle Motion Blur"), cfg.enabled)
                .setDefaultValue(true)
                .setSaveConsumer(newValue -> cfg.enabled = newValue)
                .build());

        // Third Person Rendering
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Third Person Rendering"), cfg.renderF5)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Decide whether the motion blur should be rendered in third person (F5) or not."))
                .setSaveConsumer(newValue -> cfg.renderF5 = newValue)
                .build());

        // Motion Blur Strength
        general.addEntry(entryBuilder.startFloatField(Text.literal("Motion Blur Strength"), cfg.motionBlurStrength)
                .setDefaultValue(1.0F)
                .setMin(-1000)
                .setMax(1000)
                .setTooltip(Text.literal("Sets the intensity of the blur. \n" +
                        "Default setting (1.0) blurs frames ideally in correlation to the framerate."))
                .setSaveConsumer(newValue -> cfg.motionBlurStrength = newValue)
                .build());

        // Motion Blur Sample Amount
        general.addEntry(entryBuilder.startIntField(Text.literal("Motion Blur Sample Amount"), cfg.motionBlurSamples)
                .setDefaultValue(20)
                .setMin(0)
                .setMax(1000)
                .setTooltip(Text.literal("Higher values improve visual appearance (especially at lower FPS) but impact performance negatively."))
                .setSaveConsumer(newValue -> cfg.motionBlurSamples = newValue)
                .build());

        // Blur Algorithm
        general.addEntry(entryBuilder.startEnumSelector(
                        Text.literal("Blur Algorithm"),
                        MotionBlurConfig.BlurAlgorithm.class,
                        cfg.blurAlgorithm)
                .setDefaultValue(MotionBlurConfig.BlurAlgorithm.CENTERED)
                .setTooltip(Text.literal("Changes the blur to either only blur frames behind player movement or in both directions. \n\n" +
                        "BACKWARDS has better blur continuity (less gaps in the blur) but a slight increase in perceived input lag. \n" +
                        "CENTERED has better visual uniformity (e.g. translucent objects) and no perceived increase in input lag."))
                .setSaveConsumer(newValue -> cfg.blurAlgorithm = newValue)
                .build());

        // Toggle Key
        general.addEntry(entryBuilder.startKeyCodeField(Text.literal("Toggle Key"), cfg.getToggleKey())
                .setDefaultValue(ModifierKeyCode.of(InputUtil.fromTranslationKey("key.keyboard.v"), Modifier.none()))
                .setKeySaveConsumer(newValue -> {
                    cfg.setToggleKey(newValue);
                    KeybindingManager.updateKeybinding();
                })
                .build());

        // Set save callback
        builder.setSavingRunnable(ConfigManager::saveConfig);

        // Display the config screen
        MinecraftClient.getInstance().send(() ->
                MinecraftClient.getInstance().setScreen(builder.build())
        );
    }

    //Config Screen Logic
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

    public static List<String> getAndClearErrorMessages() {
        List<String> messagesToReturn = new ArrayList<>(errorMessages);
        errorMessages.clear();

        return messagesToReturn;
    }
}