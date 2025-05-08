package net.natural.motionblur.config;

import com.mojang.brigadier.arguments.FloatArgumentType;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.ModifierKeyCode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import me.shedaniel.clothconfig2.api.Modifier;
import net.natural.motionblur.ShaderManager;

public class CommandManager {

    public static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Open config screen
            dispatcher.register(ClientCommandManager.literal("motionblur")
                    .executes(context -> {openConfigScreen();return 1;}));

            dispatcher.register(ClientCommandManager.literal("mb")
                    .executes(context -> dispatcher.execute("motionblur", context.getSource())));

            // Adjust strength
            dispatcher.register(ClientCommandManager.literal("mb")
                    .then(ClientCommandManager.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength")))));

            dispatcher.register(ClientCommandManager.literal("motionblur")
                    .then(ClientCommandManager.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength")))));
        });
    }

    private static void openConfigScreen() {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(null)
                .setTitle(Text.literal("Natural Motion Blur"));

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("Motion Blur Options"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        net.natural.motionblur.config.MotionBlurConfig config = ConfigManager.getConfig();

        // Toggle Motion Blur
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Toggle Motion Blur"), config.enabled)
                .setDefaultValue(true)
                .setSaveConsumer(newValue -> config.enabled = newValue)
                .build());

        // Third Person Rendering
        general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Third Person Rendering"), config.renderF5)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Decide whether the motion blur should be rendered in third person (F5) or not."))
                .setSaveConsumer(newValue -> config.renderF5 = newValue)
                .build());

        // Motion Blur Strength
        general.addEntry(entryBuilder.startFloatField(Text.literal("Motion Blur Strength"), config.motionBlurStrength)
                .setDefaultValue(1.0F)
                .setMin(-1000)
                .setMax(1000)
                .setTooltip(Text.literal("Sets the intensity of the blur. \n" +
                        "Default setting (1.0) blurs frames ideally in correlation to the framerate."))
                .setSaveConsumer(newValue -> config.motionBlurStrength = newValue)
                .build());

        // Motion Blur Sample Amount
        general.addEntry(entryBuilder.startIntField(Text.literal("Motion Blur Sample Amount"), config.motionBlurSamples)
                .setDefaultValue(20)
                .setMin(0)
                .setMax(1000)
                .setTooltip(Text.literal("Higher values improve visual appearance (especially at lower FPS) but impact performance negatively."))
                .setSaveConsumer(newValue -> config.motionBlurSamples = newValue)
                .build());

        // Blur Algorithm
        general.addEntry(entryBuilder.startEnumSelector(
                        Text.literal("Blur Algorithm"),
                        MotionBlurConfig.BlurAlgorithm.class,
                        config.blurAlgorithm)
                .setDefaultValue(MotionBlurConfig.BlurAlgorithm.CENTERED)
                .setTooltip(Text.literal("Changes the blur to either only blur frames behind player movement or in both directions. \n\n" +
                        "BACKWARDS has better blur continuity (less gaps in the blur) but a slight increase in perceived input lag. \n" +
                        "CENTERED has better visual uniformity (e.g. translucent objects) and no perceived increase in input lag."))
                .setSaveConsumer(newValue -> config.blurAlgorithm = newValue)
                .build());

        // Toggle Key
        general.addEntry(entryBuilder.startKeyCodeField(Text.literal("Toggle Key"), config.getToggleKey())
                .setDefaultValue(ModifierKeyCode.of(InputUtil.fromTranslationKey("key.keyboard.v"), Modifier.none()))
                .setKeySaveConsumer(newValue -> {
                    config.setToggleKey(newValue);
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

    private static int setMotionBlurStrength(float strength) {
        if (strength < -1000 || strength > 1000) {
            assert MinecraftClient.getInstance().player != null;
            MinecraftClient.getInstance().player.sendMessage(
                    Text.literal("§cInvalid motion blur strength! Value must be under 1000."),
                    false
            );
            return 0;
        }

        // Update config
        ConfigManager.getConfig().motionBlurStrength = strength;
        ConfigManager.saveConfig();

        // Update shader
        ShaderManager.updateBlurStrength(strength);

        // Notify the player
        assert MinecraftClient.getInstance().player != null;
        MinecraftClient.getInstance().player.sendMessage(
                Text.literal("Motion blur strength set to " + strength),
                false
        );

        return 1;
    }
}