package net.natural.motionblur;

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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
import com.mojang.brigadier.arguments.FloatArgumentType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MotionBlurMod implements ClientModInitializer {

    public static String ID = "naturalmotionblur";
    private float currentBlur;
    private static MotionBlurConfig config;
    private static KeyBinding toggleKeybinding;
    private static final Gson GSON = new GsonBuilder().setLenient().setPrettyPrinting().create();
    private static final ManagedShaderEffect motionblur = ShaderEffectManager.getInstance().manage(
            new Identifier(ID, "shaders/post/motion_blur.json"),
            shader -> shader.setUniformValue("BlendFactor", config.motionBlurStrength)
    );
    public enum BlurAlgorithm {BACKWARDS, CENTERED}
    private static ClientTickEvents.EndTick endTickListener;
    private static boolean configReset = false;
    private static boolean delayMessageSent = false;
    private static int tickCounter = 0;
    private static final int TICK_DELAY = 80;

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKeybinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Toggle Motion Blur",
                InputUtil.Type.KEYSYM,
                config.toggleKey,
                KeyBinding.MISC_CATEGORY));

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
                                .setTooltip(Text.literal("Sets the intensity of the blur. \n" +
                                        "Default setting (1.0) blends frames ideally in correlation to framerate."))
                                .setSaveConsumer(newValue -> config.motionBlurStrength = newValue)
                                .build());

                        general.addEntry(entryBuilder.startIntField(Text.literal("Motion Blur Sample Amount"), config.motionBlurSamples)
                                .setDefaultValue(20)
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

                        general.addEntry(entryBuilder.startKeyCodeField(Text.literal("Toggle Key"), InputUtil.fromKeyCode(config.toggleKey, 0))
                                .setDefaultValue(ModifierKeyCode.of(InputUtil.fromTranslationKey("key.keyboard.v"), Modifier.none()))
                                .setKeySaveConsumer(newValue -> {
                                    config.toggleKey = newValue.getCode();
                                    MinecraftClient.getInstance().options.setKeyCode(toggleKeybinding, newValue);
                                    KeyBinding.updateKeysByCode();
                                })
                                .build());
                        builder.setSavingRunnable(this::saveConfig);
                        MinecraftClient.getInstance().send(() ->
                                MinecraftClient.getInstance().setScreen(builder.build())
                        );
                        return 1;
                    }));
            dispatcher.register(ClientCommandManager.literal("mb").executes(context -> {
                return dispatcher.execute("motionblur", context.getSource());
            }));
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
                endTickListener = tickClient -> {
                    if (tickCounter >= TICK_DELAY && !delayMessageSent) {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§cNatural Motion Blur has encountered an issue and has been reset to its default settings."), false);
                            delayMessageSent = true; // Mark message as sent
                        }
                    } else {
                        tickCounter++;
                    }
                };
                ClientTickEvents.END_CLIENT_TICK.register(endTickListener);
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
    private void loadConfig() {
        File configFile = FabricLoader.getInstance().getConfigDir().resolve("naturalmotionblur.json").toFile();
        if (!configFile.exists()) {
            config = new MotionBlurConfig();
            saveConfig();
        } else {
            try {
                config = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), MotionBlurConfig.class);
                config.blurAlgorithm = BlurAlgorithm.values()[config.blurAlgorithm.ordinal()];
            } catch (Exception e) {
                config = new MotionBlurConfig();
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
