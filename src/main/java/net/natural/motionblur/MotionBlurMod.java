package net.natural.motionblur;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.natural.motionblur.config.MotionBlurConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ladysnake.satin.api.event.PostWorldRenderCallbackV2;
import ladysnake.satin.api.managed.ManagedShaderEffect;
import ladysnake.satin.api.managed.ShaderEffectManager;
import me.shedaniel.clothconfig2.api.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.commons.io.FileUtils;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MotionBlurMod implements ClientModInitializer {

    public static String ID = "naturalmotionblur";
    private float currentBlur;
    private static MotionBlurConfig config;
    private static KeyBinding toggleKeybinding;
    private static final Gson GSON = new GsonBuilder().setLenient().setPrettyPrinting().create();
    private static final ManagedShaderEffect motionblur = ShaderEffectManager.getInstance().manage(
            Identifier.of(ID, "shaders/post/motion_blur.json"),
            shader -> shader.setUniformValue("BlendFactor", config.motionBlurStrength)
    );
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    private static boolean configReset = false;
    private static boolean delayMessageSent = false;
    private static int tickCounter = 0;
    private static final int TICK_DELAY = 80;

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKeybinding = new KeyBinding(
                Text.literal("Toggle Motion Blur").getString(),
                config.getToggleKey().getCode(),
                KeyBinding.MISC_CATEGORY);

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if(toggleKeybinding.wasPressed()){
                config.enabled = !config.enabled;
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("motionblur")
                    .executes(context -> {
                        ConfigBuilder builder = ConfigBuilder.create()
                                .setParentScreen(null)
                                .setTitle(Text.literal("Natural Motion Blur"));
                        ConfigCategory general = builder.getOrCreateCategory(Text.literal("Motion Blur Options"));

                        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

                        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Toggle Motion Blur"), config.enabled)
                                .setDefaultValue(true)
                                .setSaveConsumer(newValue -> config.enabled = newValue)
                                .build());

                        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Third Person Rendering"), config.renderF5)
                                .setDefaultValue(true)
                                .setTooltip(Text.literal("Decide whether the motion blur should be rendered in third person (F5) or not."))
                                .setSaveConsumer(newValue -> config.renderF5 = newValue)
                                .build());

                        general.addEntry(entryBuilder.startFloatField(Text.literal("Motion Blur Strength"), config.motionBlurStrength)
                                .setDefaultValue(1.0F)
                                .setMin(-1000)
                                .setMax(1000)
                                .setTooltip(Text.literal("Sets the intensity of the blur. \n" +
                                        "Default setting (1.0) blends frames ideally in correlation to framerate."))
                                .setSaveConsumer(newValue -> config.motionBlurStrength = newValue)
                                .build());

                        general.addEntry(entryBuilder.startIntField(Text.literal("Motion Blur Sample Amount"), config.motionBlurSamples)
                                .setDefaultValue(20)
                                .setMin(0)
                                .setMax(1000)
                                .setTooltip(Text.literal("Higher values improve visual appearance (especially at lower FPS) but impact performance negatively."))
                                .setSaveConsumer(newValue -> config.motionBlurSamples = newValue)
                                .build());

                        general.addEntry(entryBuilder.startEnumSelector(
                                        Text.literal("Blur Algorithm"),
                                        BlurAlgorithm.class,
                                        config.blurAlgorithm)
                                .setDefaultValue(BlurAlgorithm.CENTERED)
                                .setTooltip(Text.literal("Changes the blur to either only blend frames backwards or in both directions. \n\n" +
                                        "BACKWARDS has better blur continuity (better frame blending). \n" +
                                        "CENTERED has better visual uniformity (e.g. translucent objects) and less perceived input lag."))
                                .setSaveConsumer(newValue -> config.blurAlgorithm = newValue)
                                .build());

                        general.addEntry(entryBuilder.startKeyCodeField(Text.literal("Toggle Key"), config.getToggleKey())
                                .setDefaultValue(ModifierKeyCode.of(InputUtil.fromTranslationKey("key.keyboard.v"), Modifier.none()))
                                .setKeySaveConsumer(newValue -> {
                                    config.setToggleKey(newValue);
                                    toggleKeybinding.setBoundKey(newValue);
                                    KeyBinding.updateKeysByCode();
                                })
                                .build());
                        builder.setSavingRunnable(this::saveConfig);
                        MinecraftClient.getInstance().send(() ->
                                MinecraftClient.getInstance().setScreen(builder.build())
                        );
                        return 1;
                    }));
            dispatcher.register(ClientCommandManager.literal("mb").executes(context ->
                    dispatcher.execute("motionblur", context.getSource())));
            dispatcher.register(ClientCommandManager.literal("mb")
                    .then(ClientCommandManager.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength"))))
            );
            dispatcher.register(ClientCommandManager.literal("motionblur")
                    .then(ClientCommandManager.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength"))))
            );
        });

        PostWorldRenderCallbackV2.EVENT.register((matrix, camera, deltaTick, a) -> {
            if (config.motionBlurStrength != 0 && config.enabled) {
                if (!IrisCheck.checkIrisShouldDisable()) {
                    return;
                }
                if (currentBlur != config.motionBlurStrength) {
                    motionblur.setUniformValue("BlendFactor", config.motionBlurStrength);
                    currentBlur = config.motionBlurStrength;
                }
                motionblur.setUniformValue("view_res", (float) MinecraftClient.getInstance().getFramebuffer().viewportWidth, (float) MinecraftClient.getInstance().getFramebuffer().viewportHeight);
                motionblur.setUniformValue("view_pixel_size", 1.0f / MinecraftClient.getInstance().getFramebuffer().viewportWidth, 1.0f / MinecraftClient.getInstance().getFramebuffer().viewportHeight);
                motionblur.setUniformValue("motionBlurSamples", config.motionBlurSamples);
                motionblur.setUniformValue("blurAlgorithm", config.blurAlgorithm.ordinal());
                if(!MinecraftClient.getInstance().options.getPerspective().isFirstPerson() && !config.renderF5) return;

                motionblur.render(deltaTick);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (configReset) {
                tickCounter = 0;
                delayMessageSent = false;
                ClientTickEvents.END_CLIENT_TICK.register(tickClient -> {
                    if (tickCounter >= TICK_DELAY && !delayMessageSent) {
                        if (client.player != null) {
                            for (String errorMessage : errorMessages) {
                                client.player.sendMessage(Text.literal("§c" + errorMessage), false);
                            }
                            delayMessageSent = true;
                            errorMessages.clear();
                        }
                    } else {
                        tickCounter++;
                    }
                });
            }
        });
    }
    private int setMotionBlurStrength(float strength) {
        config.motionBlurStrength = strength;
        saveConfig();
        motionblur.setUniformValue("BlendFactor", strength);
        assert MinecraftClient.getInstance().player != null;
        MinecraftClient.getInstance().player.sendMessage(Text.literal("Motion Blur Strength set to " + strength), false);
        return 1;
    }
    private final List<String> errorMessages = new ArrayList<>();
    private void loadConfig() {
        File configFile = FabricLoader.getInstance().getConfigDir().resolve("naturalmotionblur.json").toFile();
        boolean configModified = false;
        errorMessages.clear();

        if (!configFile.exists()) {
            config = new MotionBlurConfig();
            saveConfig();
        } else {
            try {
                JsonObject configJson = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), JsonObject.class);
                config = new MotionBlurConfig();
                if (configJson.has("motionBlurStrength")) {
                    try {
                        config.motionBlurStrength = configJson.get("motionBlurStrength").getAsFloat();
                        if (config.motionBlurStrength < -1000 || config.motionBlurStrength > 1000) {
                            config.motionBlurStrength = 1.0f;
                            errorMessages.add("Strength value of mod \"Natural Motion Blur\" was invalid and has been reset to default (1.0).");
                            configModified = true;
                        }
                    } catch (Exception e) {
                        config.motionBlurStrength = 1.0f;
                        errorMessages.add("Strength value of mod \"Natural Motion Blur\" was invalid and has been reset to default (1.0).");
                        configModified = true;
                    }
                }
                if (configJson.has("motionBlurSamples")) {
                    try {
                        config.motionBlurSamples = configJson.get("motionBlurSamples").getAsInt();
                        if (config.motionBlurSamples < 0 || config.motionBlurSamples > 1000) {
                            config.motionBlurSamples = 20;
                            errorMessages.add("Sample amount of mod \"Natural Motion Blur\" was invalid and has been reset to default (20).");
                            configModified = true;
                        }
                    } catch (Exception e) {
                        config.motionBlurSamples = 20;
                        errorMessages.add("Sample amount of mod \"Natural Motion Blur\" was invalid and has been reset to default (20).");
                        configModified = true;
                    }
                }
                if (configJson.has("blurAlgorithm")) {
                    try {
                        config.blurAlgorithm = BlurAlgorithm.valueOf(configJson.get("blurAlgorithm").getAsString().toUpperCase());
                    } catch (Exception e) {
                        config.blurAlgorithm = BlurAlgorithm.CENTERED;
                        errorMessages.add("Blur algorithm of mod \"Natural Motion Blur\" was invalid and has been reset to default (CENTERED).");
                        configModified = true;
                    }
                }
                if (configJson.has("toggleKey")) {
                    try {
                        String key = configJson.get("toggleKey").getAsString();
                        InputUtil.Key parsedKey = InputUtil.fromTranslationKey(key);

                        if (parsedKey == null || key.trim().isEmpty()) {
                            throw new IllegalArgumentException("Invalid toggleKey.");
                        }
                        config.setToggleKey(parsedKey);
                    } catch (Exception e) {
                        config.setToggleKey(InputUtil.fromTranslationKey("key.keyboard.v"));
                        errorMessages.add("Toggle key of mod \"Natural Motion Blur\" was invalid and has been reset to default (V).");
                        configModified = true;
                    }
                }
                if (configJson.has("renderF5")) {
                    try {
                        String renderF5Value = configJson.get("renderF5").getAsString();
                        if ("true".equalsIgnoreCase(renderF5Value) || "false".equalsIgnoreCase(renderF5Value)) {
                            config.renderF5 = Boolean.parseBoolean(renderF5Value);
                        } else {
                            throw new IllegalArgumentException("Invalid renderF5 value.");
                        }
                    } catch (Exception e) {
                        config.renderF5 = true;
                        errorMessages.add("Third person rendering option of mod \"Natural Motion Blur\" was invalid and has been reset to default (enabled).");
                        configModified = true;
                    }
                }
                if (configJson.has("enabled")) {
                    try {
                        String enabledValue = configJson.get("enabled").getAsString();
                        if ("true".equalsIgnoreCase(enabledValue) || "false".equalsIgnoreCase(enabledValue)) {
                            config.enabled = Boolean.parseBoolean(enabledValue);
                        } else {
                            throw new IllegalArgumentException("Invalid enabled value.");
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

    private void saveConfig() {
        File configFile = FabricLoader.getInstance().getConfigDir().resolve("naturalmotionblur.json").toFile();
        try {
            FileUtils.write(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView, Matrix4f projection, Matrix4f prevProjection, Vector3f cameraPos, Vector3f prevCameraPos) {
        motionblur.setUniformValue("modelView", new Matrix4f(modelView));
        motionblur.setUniformValue("prevModelView", new Matrix4f(prevModelView));
        motionblur.setUniformValue("projection", new Matrix4f(projection));
        motionblur.setUniformValue("prevProjection", new Matrix4f(prevProjection));
        motionblur.setUniformValue("projInverse", new Matrix4f(projection).invert());
        motionblur.setUniformValue("mvInverse", new Matrix4f(modelView).invert());
        motionblur.setUniformValue("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        motionblur.setUniformValue("prevCameraPos", prevCameraPos.x, prevCameraPos.y, prevCameraPos.z);
    }
}