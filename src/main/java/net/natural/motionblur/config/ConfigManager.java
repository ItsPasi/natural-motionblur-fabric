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
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.natural.motionblur.recording.RecordingShaderManager;
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
    public static Screen createConfigScreen(Screen parent) {
        ConfigEntries cfg = getConfig();
        sanitizeConfigState(cfg);

        var refreshRateScalingOption = Option.<Boolean>createBuilder()
                .name(Component.literal("Refresh Rate Scaling"))
                .description(OptionDescription.of(Component.empty()
                        .append(Component.literal("If enabled, motion blur strength will adjust automatically based on FPS relative to your monitor's refresh rate. This helps in preventing stroboscopic effect visibility on high FPS.\n\n"))
                        .append(Component.literal("When disabled, the blur strength is fixed to the set value.\n\n"))
                        .append(Component.literal("Example\n").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                        .append(Component.literal("1. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                        .append(Component.literal("600 FPS on a 60 Hz monitor → strength is increased by 10x\n").withStyle(s -> s.withColor(0x5599FF)))
                        .append(Component.literal("2. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                        .append(Component.literal("60 FPS or less on a 60 Hz monitor → strength is not changed").withStyle(s -> s.withColor(0x5599FF)))))
                .binding(true, () -> cfg.refreshRateScaling, newValue -> cfg.refreshRateScaling = newValue)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true))
                .available(cfg.allowsRefreshRateScaling())
                .build();

        var strengthOption = Option.<Float>createBuilder()
                .name(Component.literal("Motion Blur Strength"))
                .description(OptionDescription.of(Component.literal("""
                        Sets the intensity of the blur.
                        \s
                        Default setting (1.0) blurs frames ideally in correlation to the framerate.""")))
                .binding(1.0F, () -> cfg.motionBlurStrength, newValue -> cfg.motionBlurStrength = newValue)
                .controller(opt -> FloatSliderControllerBuilder.create(opt).range(0f, 2f).step(0.1f))
                .available(cfg.showsStrengthSlider())
                .build();

        var algorithmOption = Option.<ConfigEntries.BlurAlgorithm>createBuilder()
                .name(Component.literal("Blur Algorithm"))
                .description(OptionDescription.of(Component.empty()
                        .append(Component.literal("Changes how motion blur is rendered.\n\n"))
                        .append(Component.literal("Velocity Based").withStyle(style -> style.withColor(0x5599FF).withBold(true))).append(Component.literal(" (Recommended)").withStyle(style -> style.withColor(0xAAAAAA)))
                        .append(Component.literal("\nUses velocity information to blur in the direction of movement. This is the only blur option with no increase in perceived input lag.\n"))
                        .append(Component.literal("Similar to shader packs like BSL, Complementary and Labymod client, but improved and optimized.\n\n").withStyle(style -> style.withColor(0xAAAAAA).withItalic(true)))
                        .append(Component.literal("Frame Blending").withStyle(style -> style.withColor(0xFFFF55).withBold(true)))
                        .append(Component.literal("\nBlends additional frames between each displayed frame into the current image.\n"))
                        .append(Component.literal("Recreates the effect of post-processing tools like blur by f0e, Premiere Pro, and DaVinci Resolve.\n\n").withStyle(style -> style.withColor(0xAAAAAA).withItalic(true)))
                        .append(Component.literal("Hybrid Blending").withStyle(style -> style.withColor(0xFFFF55).withBold(true)))
                        .append(Component.literal("\nRuns velocity based blur and frame blending at the same time.\n"))
                        .append(Component.literal("Creates a smoother result with less artifacts but may impact performance.\n\n").withStyle(style -> style.withColor(0xAAAAAA).withItalic(true)))
                        .append(Component.literal("Accumulation MAX").withStyle(style -> style.withColor(0xFF5555).withBold(true)))
                        .append(Component.literal("\nCreates a blur trail with high brightness.\n"))
                        .append(Component.literal("Matches LABYMOD MIX, LUNAR V1, BLC 2.0.\n\n").withStyle(style -> style.withColor(0xAAAAAA).withItalic(true)))
                        .append(Component.literal("Accumulation MIX").withStyle(style -> style.withColor(0xFF5555).withBold(true)))
                        .append(Component.literal("\nCreates a blur trail with even brightness.\n"))
                        .append(Component.literal("Matches LABYMOD MAX, LUNAR V2/V3, BLC 3.0/Badlion.").withStyle(style -> style.withColor(0xAAAAAA).withItalic(true)))))
                .binding(ConfigEntries.BlurAlgorithm.VELOCITY_BASED, () -> cfg.blurAlgorithm, newValue -> cfg.blurAlgorithm = newValue)
                .listener((opt, newValue) -> {
                    if (newValue == ConfigEntries.BlurAlgorithm.HYBRID_BLENDING) {
                        cfg.refreshRateScaling = false;
                        cfg.motionBlurStrength = 1.0F;
                    }
                    refreshRateScalingOption.setAvailable(newValue == ConfigEntries.BlurAlgorithm.VELOCITY_BASED);
                    strengthOption.setAvailable(newValue != ConfigEntries.BlurAlgorithm.FRAME_BLENDING
                            && newValue != ConfigEntries.BlurAlgorithm.HYBRID_BLENDING);
                })
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(ConfigEntries.BlurAlgorithm.class)
                        .valueFormatter(value -> switch (value) {
                            case VELOCITY_BASED   -> Component.literal("Velocity Based").withStyle(s -> s.withColor(0x5599FF));
                            case FRAME_BLENDING   -> Component.literal("Frame Blending").withStyle(s -> s.withColor(0xFFFF55));
                            case HYBRID_BLENDING  -> Component.literal("Hybrid Blending").withStyle(s -> s.withColor(0xFFFF55));
                            case ACCUMULATION_MAX -> Component.literal("Accumulation MAX").withStyle(s -> s.withColor(0xFF5555));
                            case ACCUMULATION_MIX -> Component.literal("Accumulation MIX").withStyle(s -> s.withColor(0xFF5555));
                        }))
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Natural Motion Blur"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Motion Blur Options"))
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Motion Blur"))
                                .binding(true, () -> cfg.enabled, newValue -> cfg.enabled = newValue)
                                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true))
                                .build())
                        .option(refreshRateScalingOption)
                        .option(strengthOption)
                        .option(algorithmOption)
                        .build())

                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Recording Output"))

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Enable OBS Spout Output"))
                                .description(OptionDescription.of(Component.empty()
                                        .append(Component.literal("Sends a different blur setup directly to OBS.\n\n"))
                                        .append(Component.literal("Requires the OBS Spout2 Plugin (Windows only).\n\n"))
                                        .append(Component.literal("Setup\n").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                                        .append(Component.literal("1. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                                        .append(Component.literal("Download the Spout2 Plugin Installer from github.com/Off-World-Live/obs-spout2-plugin\n").withStyle(s -> s.withColor(0x5599FF)))
                                        .append(Component.literal("2. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                                        .append(Component.literal("Run the installer, then enable the plugin via OBS > Tools > Plugin Manager\n").withStyle(s -> s.withColor(0x5599FF)))
                                        .append(Component.literal("3. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                                        .append(Component.literal("In OBS, add a new Source and select 'Spout2 Capture'\n").withStyle(s -> s.withColor(0x5599FF)))
                                        .append(Component.literal("4. ").withStyle(s -> s.withColor(0x5599FF).withBold(true)))
                                        .append(Component.literal("Enable this option - the feed should appear in OBS automatically\n\n").withStyle(s -> s.withColor(0x5599FF)))
                                        .append(Component.literal("⚠ Disclaimer\n").withStyle(s -> s.withColor(0xFF5555).withBold(true)))
                                        .append(Component.literal("Since this runs an additional frame blending layer for the OBS output, follow either of these rules for ideal results:\n\n").withStyle(s -> s.withColor(0xFF5555)))
                                        .append(Component.literal("A. ").withStyle(s -> s.withColor(0xFF5555).withBold(true)))
                                        .append(Component.literal("Turn off motion blur and play at any FPS setting\n").withStyle(s -> s.withColor(0xFF5555)))
                                        .append(Component.literal("B. ").withStyle(s -> s.withColor(0xFF5555).withBold(true)))
                                        .append(Component.literal("Turn on any motion blur and limit FPS to your monitor's refresh rate\n").withStyle(s -> s.withColor(0xFF5555)))
                                        .append(Component.literal("C. ").withStyle(s -> s.withColor(0xFF5555).withBold(true)))
                                        .append(Component.literal("Turn on velocity blur, turn off refresh rate scaling and play at any FPS setting").withStyle(s -> s.withColor(0xFF5555)))))
                                .binding(false, () -> cfg.recordingOverlayEnabled, newVal -> {
                                    cfg.recordingOverlayEnabled = newVal;
                                    if (!newVal) RecordingShaderManager.destroy();
                                })
                                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Recording FPS Target"))
                                .description(OptionDescription.of(Component.literal("""
                                        FPS target for the Spout recording output.""")))
                                .binding(60, () -> cfg.recordingOverlayTargetFPS, newVal -> cfg.recordingOverlayTargetFPS = newVal)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(24, 240).step(1))
                                .build())

                        .build())
                .save(ConfigManager::saveConfig)
                .build()
                .generateScreen(parent);
    }

    public static void openConfigScreen() {
        var screen = createConfigScreen(null);
        Minecraft.getInstance().schedule(() ->
                Minecraft.getInstance().setScreen(screen)
        );
    }

    // Config Screen Logic
    public static void loadConfig() {
        File configFile = getConfigFile();
        errorMessages.clear();

        config = new ConfigEntries();

        if (!configFile.exists()) {
            saveConfig();
            return;
        }

        try {
            JsonObject json = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) { saveConfig(); return; }
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
                try { float v = json.get("motionBlurStrength").getAsFloat(); if (v < 0.0F || v > 2.0F) throw new IllegalArgumentException(); config.motionBlurStrength = v; }
                catch (Exception e) { config.motionBlurStrength = 1.0F; errorMessages.add("Motion Blur Strength option of \"Natural Motion Blur\" was invalid and has been reset to default (1.0)."); modified = true; }
            }
            if (json.has("blurAlgorithm")) {
                try { config.blurAlgorithm = ConfigEntries.BlurAlgorithm.valueOf(json.get("blurAlgorithm").getAsString().toUpperCase()); }
                catch (Exception e) { config.blurAlgorithm = ConfigEntries.BlurAlgorithm.VELOCITY_BASED; errorMessages.add("Blur Algorithm option of \"Natural Motion Blur\" was invalid and has been reset to default (Velocity Based)."); modified = true; }
            }
            ConfigEntries sanitizedConfig = new ConfigEntries();
            sanitizedConfig.enabled = config.enabled;
            sanitizedConfig.refreshRateScaling = config.refreshRateScaling;
            sanitizedConfig.motionBlurStrength = config.motionBlurStrength;
            sanitizedConfig.recordingOverlayEnabled = config.recordingOverlayEnabled;
            sanitizedConfig.recordingOverlayTargetFPS = config.recordingOverlayTargetFPS;
            sanitizedConfig.blurAlgorithm = config.blurAlgorithm;
            sanitizeConfigState(sanitizedConfig);
            if (sanitizedConfig.refreshRateScaling != config.refreshRateScaling
                    || sanitizedConfig.motionBlurStrength != config.motionBlurStrength) {
                config.refreshRateScaling = sanitizedConfig.refreshRateScaling;
                config.motionBlurStrength = sanitizedConfig.motionBlurStrength;
                modified = true;
            }
            if (json.has("recordingOverlayTargetFPS")) {
                try {
                    int hz = json.get("recordingOverlayTargetFPS").getAsInt();
                    if (hz < 24 || hz > 240) throw new IllegalArgumentException();
                    config.recordingOverlayTargetFPS = hz;
                } catch (Exception e) { config.recordingOverlayTargetFPS = 60; errorMessages.add("OBS Spout Output FPS Target option of \"Natural Motion Blur\" was invalid and has been reset to default (60)."); modified = true; }
            }
            // OBS Spout Output is runtime-only and must always start disabled on launch.
            config.recordingOverlayEnabled = false;
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

    private static void sanitizeConfigState(ConfigEntries cfg) {
        if (!cfg.allowsRefreshRateScaling()) {
            cfg.refreshRateScaling = false;
        }
        if (cfg.locksStrengthToOne()) {
            cfg.motionBlurStrength = 1.0F;
        }
    }

    public static void saveConfig() {
        if (config != null) sanitizeConfigState(config);
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