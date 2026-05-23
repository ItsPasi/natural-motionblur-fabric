package net.natural.motionblur.config;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class CommandManager {

    public static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Open config screen
            dispatcher.register(ClientCommands.literal("motionblur")
                    .executes(context -> { ConfigManager.openConfigScreen(); return 1; }));

            dispatcher.register(ClientCommands.literal("mb")
                    .executes(context -> dispatcher.execute("motionblur", context.getSource())));

            // Adjust strength
            dispatcher.register(ClientCommands.literal("mb")
                    .then(ClientCommands.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength")))));

            dispatcher.register(ClientCommands.literal("motionblur")
                    .then(ClientCommands.argument("strength", FloatArgumentType.floatArg())
                            .executes(context -> setMotionBlurStrength(FloatArgumentType.getFloat(context, "strength")))));
        });
    }

    private static int setMotionBlurStrength(float strength) {
        if (strength < 0.0f || strength > 2.0f) {
            assert Minecraft.getInstance().player != null;
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§cInvalid motion blur strength! Value must be between 0 and 2.")
            );
            return 0;
        }

        ConfigManager.getConfig().motionBlurStrength = strength;
        ConfigManager.saveConfig();
        assert Minecraft.getInstance().player != null;
        Minecraft.getInstance().player.sendSystemMessage(
                Component.literal("Motion blur strength set to " + strength)
        );
        return 1;
    }
}