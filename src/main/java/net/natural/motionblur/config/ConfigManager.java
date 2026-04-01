package net.natural.motionblur.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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

                        // Motion Blur Toggle
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Motion Blur"))
                                .binding(true, () -> cfg.enabled, newValue -> cfg.enabled = newValue)
                                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true))
                                .build())

                        // Refresh Rate Scaling Toggle
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Refresh Rate Scaling"))
                                .description(OptionDescription.of(Component.literal("""
                                        If enabled, motion blur strength will adjust automatically based on FPS relative to your display's refresh rate.
                                        \s
                                        When disabled, the blur strength is fixed to the set value.""")))
                                .binding(true, () -> cfg.refreshRateScaling, newValue -> cfg.refreshRateScaling = newValue)
                                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true))
                                .build())

                        // Motion Blur Strength Slider
                        .option(Option.<Float>createBuilder()
                                .name(Component.literal("Motion Blur Strength"))
                                .description(OptionDescription.of(Component.literal("""
                                        Sets the intensity of the blur.
                                        \s
                                        Default setting (1.0) blurs frames ideally in correlation to the framerate.""")))
                                .binding(1.0F, () -> cfg.motionBlurStrength, newValue -> cfg.motionBlurStrength = newValue)
                                .controller(opt -> FloatSliderControllerBuilder.create(opt).range(0f, 10f).step(0.1f))
                                .build())

                        .option(Option.<ConfigEntries.BlurAlgorithm>createBuilder()
                                .name(Component.literal("Blur Algorithm"))
                                .description(OptionDescription.of(Component.literal("""
                                        Changes how motion blur is rendered.
                                        \s
                                        \s
                                        VELOCITY_BASED uses velocity to blur in the direction of movement each frame.
                                        \s
                                        FRAME_BLENDING blends extra rendered frames into the current image.""")))
                                .binding(ConfigEntries.BlurAlgorithm.VELOCITY_BASED, () -> cfg.blurAlgorithm, newValue -> cfg.blurAlgorithm = newValue)
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
        errorMessages.clear();

        if (!configFile.exists()) {
            config = new ConfigEntries();
            saveConfig();
            return;
        }

        try {
            JsonObject json = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), JsonObject.class);
            config = new ConfigEntries();
            boolean modified = false;

            if (json.has("enabled")) {
                try { config.enabled = parseBool(json.get("enabled").getAsString()); }
                catch (Exception e) { config.enabled = true; errorMessages.add("Toggle option of \"Natural Motion Blur\" was invalid and has been reset to default (enabled)."); modified = true; }
            }
            if (json.has("refreshRateScaling")) {
                try { config.refreshRateScaling = parseBool(json.get("refreshRateScaling").getAsString()); }
                catch (Exception e) { config.refreshRateScaling = true; errorMessages.add("Refresh Rate Scaling option of \"Natural Motion Blur\" was invalid and has been reset to default (enabled)."); modified = true; }
            }
            if (json.has("motionBlurStrength")) {
                try { float v = json.get("motionBlurStrength").getAsFloat(); if (v < 0.0F || v > 10.0F) throw new IllegalArgumentException(); config.motionBlurStrength = v; }
                catch (Exception e) { config.motionBlurStrength = 1.0F; errorMessages.add("Motion Blur Strength option of \"Natural Motion Blur\" was invalid and has been reset to default (1.0)."); modified = true; }
            }
            if (json.has("blurAlgorithm")) {
                try { config.blurAlgorithm = ConfigEntries.BlurAlgorithm.valueOf(json.get("blurAlgorithm").getAsString().toUpperCase()); }
                catch (Exception e) { config.blurAlgorithm = ConfigEntries.BlurAlgorithm.VELOCITY_BASED; errorMessages.add("Blur Algorithm option of \"Natural Motion Blur\" was invalid and has been reset to default (VELOCITY_BASED)."); modified = true; }
            }

            if (modified) { saveConfig(); configReset = true; }

        } catch (Exception e) {
            config = new ConfigEntries();
            saveConfig();
            configReset = true;
            errorMessages.add("Config file of mod \"Natural Motion Blur\" could not be loaded correctly and has been reset to default.");
        }
    }

    private static boolean parseBool(String s) {
        if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) throw new IllegalArgumentException();
        return Boolean.parseBoolean(s);
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