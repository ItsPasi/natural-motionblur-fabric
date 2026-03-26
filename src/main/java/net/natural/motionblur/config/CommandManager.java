package net.natural.motionblur.config;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.natural.motionblur.ShaderManager;

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
        if (strength < -1000 || strength > 1000) {
            assert Minecraft.getInstance().player != null;
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§cInvalid motion blur strength! Value must be under 1000.")
            );
            return 0;
        }

        ConfigManager.getConfig().motionBlurStrength = strength;
        ConfigManager.saveConfig();
        ShaderManager.updateBlurStrength(strength);
        assert Minecraft.getInstance().player != null;
        Minecraft.getInstance().player.sendSystemMessage(
                Component.literal("Motion blur strength set to " + strength)
        );
        return 1;
    }
}