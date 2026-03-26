package net.natural.motionblur.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> errorMessages = new ArrayList<>();
    private static boolean configReset = false;

    private static ConfigEntries config;

    public static ConfigEntries getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    // Config Screen Interface
    public static void openConfigScreen() {
        ConfigEntries cfg = getConfig();

        var screen = YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Natural Motion Blur"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Motion Blur Options"))

                        // Toggle Motion Blur
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Toggle Motion Blur"))
                                .binding(true, () -> cfg.enabled, newValue -> cfg.enabled = newValue)
                                .controller(TickBoxControllerBuilder::create)
                                .build())

                        // Third Person Rendering
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Third Person Rendering"))
                                .description(OptionDescription.of(Component.literal(
                                        "Decide whether the motion blur should be rendered in third person (F5) or not.")))
                                .binding(true, () -> cfg.renderF5, newValue -> cfg.renderF5 = newValue)
                                .controller(TickBoxControllerBuilder::create)
                                .build())

                        // Use Refresh Rate Scaling
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Use Refresh Rate Scaling"))
                                .description(OptionDescription.of(Component.literal(
                                        "If enabled, motion blur strength will adjust automatically based on FPS relative to your display's refresh rate.\n" +
                                                "When disabled, the blur strength is fixed to the set value.")))
                                .binding(true, () -> cfg.refreshRateScaling, newValue -> cfg.refreshRateScaling = newValue)
                                .controller(TickBoxControllerBuilder::create)
                                .build())

                        // Use Depth Blur
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Use Depth Blur"))
                                .description(OptionDescription.of(
                                        Component.literal("""
                                                If enabled, the mod will use depth information for movement blur.
                                                When disabled, only mouse movement will be blurred.\s

                                                This setting is incompatible with\s""")
                                                .append(Component.literal("Fabulous!").withStyle(ChatFormatting.ITALIC))
                                                .append(" graphics. Depth blur will not work regardless of this setting.")))
                                .binding(true, () -> cfg.depthBlur, newValue -> cfg.depthBlur = newValue)
                                .controller(TickBoxControllerBuilder::create)
                                .build())

                        // Motion Blur Strength
                        .option(Option.<Float>createBuilder()
                                .name(Component.literal("Motion Blur Strength"))
                                .description(OptionDescription.of(Component.literal(
                                        "Sets the intensity of the blur.\n" +
                                                "Default setting (1.0) blurs frames ideally in correlation to the framerate.")))
                                .binding(1.0F, () -> cfg.motionBlurStrength, newValue -> cfg.motionBlurStrength = newValue)
                                .controller(opt -> FloatFieldControllerBuilder.create(opt).range(-1000f, 1000f))
                                .build())

                        // Blur Algorithm
                        .option(Option.<ConfigEntries.BlurAlgorithm>createBuilder()
                                .name(Component.literal("Blur Algorithm"))
                                .description(OptionDescription.of(Component.literal("""
                                        Changes the blur to either only blur frames behind player movement or in both directions.\s

                                        BACKWARDS has better blur continuity (less gaps in the blur) but a slight increase in perceived input lag.\s
                                        CENTERED has better visual uniformity (e.g. translucent objects) and no perceived increase in input lag.""")))
                                .binding(ConfigEntries.BlurAlgorithm.CENTERED, () -> cfg.blurAlgorithm, newValue -> cfg.blurAlgorithm = newValue)
                                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(ConfigEntries.BlurAlgorithm.class))
                                .build())

                        .build())
                .save(ConfigManager::saveConfig)
                .build()
                .generateScreen(null);

        Minecraft.getInstance().schedule(() ->
                Minecraft.getInstance().setScreen(screen)
        );
    }

    // Config Screen Logic
    public static void loadConfig() {
        File configFile = getConfigFile();
        boolean configModified = false;
        errorMessages.clear();

        if (!configFile.exists()) {
            config = new ConfigEntries();
            saveConfig();
        } else {
            try {
                JsonObject configJson = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), JsonObject.class);
                config = new ConfigEntries();

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

                // Process refreshRateScaling
                if (configJson.has("refreshRateScaling")) {
                    try {
                        String scalingValue = configJson.get("refreshRateScaling").getAsString();
                        if ("true".equalsIgnoreCase(scalingValue) || "false".equalsIgnoreCase(scalingValue)) {
                            config.refreshRateScaling = Boolean.parseBoolean(scalingValue);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.refreshRateScaling = true;
                        errorMessages.add("Refresh rate scaling option of mod \"Natural Motion Blur\" was invalid and has been reset to default (enabled).");
                        configModified = true;
                    }
                }

                // Process depthBlur
                if (configJson.has("depthBlur")) {
                    try {
                        String val = configJson.get("depthBlur").getAsString();
                        if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                            config.depthBlur = Boolean.parseBoolean(val);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } catch (Exception e) {
                        config.depthBlur = true;
                        errorMessages.add("Toggle option of \"Use Depth Blur\" was invalid and has been reset to default (enabled).");
                        configModified = true;
                    }
                }

                // Process motionBlurStrength
                if (configJson.has("motionBlurStrength")) {
                    try {
                        float strength = configJson.get("motionBlurStrength").getAsFloat();
                        if (strength < -1000.0F || strength > 1000.0F) {
                            throw new IllegalArgumentException();
                        }
                        config.motionBlurStrength = strength;
                    } catch (Exception e) {
                        config.motionBlurStrength = 1.0F;
                        errorMessages.add("Strength value of mod \"Natural Motion Blur\" was invalid and has been reset to default (1.0).");
                        configModified = true;
                    }
                }

                // Process blurAlgorithm
                if (configJson.has("blurAlgorithm")) {
                    try {
                        config.blurAlgorithm = ConfigEntries.BlurAlgorithm.valueOf(configJson.get("blurAlgorithm").getAsString().toUpperCase());
                    } catch (Exception e) {
                        config.blurAlgorithm = ConfigEntries.BlurAlgorithm.CENTERED;
                        errorMessages.add("Blur algorithm of mod \"Natural Motion Blur\" was invalid and has been reset to default (CENTERED).");
                        configModified = true;
                    }
                }
            } catch (Exception e) {
                config = new ConfigEntries();
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