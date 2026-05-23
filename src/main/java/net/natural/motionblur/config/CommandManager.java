package net.natural.motionblur.config;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class CommandManager {

    public static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Open config screen
            dispatcher.register(ClientCommandManager.literal("motionblur")
                    .executes(context -> { ConfigManager.openConfigScreen(); return 1; }));

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

    private static int setMotionBlurStrength(float strength) {
        if (strength < 0.0f || strength > 2.0f) {
            assert Minecraft.getInstance().player != null;
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§cInvalid motion blur strength! Value must be between 0 and 2."), false
            );
            return 0;
        }

        ConfigManager.getConfig().motionBlurStrength = strength;
        ConfigManager.saveConfig();
        assert Minecraft.getInstance().player != null;
        Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Motion blur strength set to " + strength), false
        );
        return 1;
    }
}