package net.natural.motionblur.config;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.natural.motionblur.ShaderManager;

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
        if (strength < -1000 || strength > 1000) {
            assert MinecraftClient.getInstance().player != null;
            MinecraftClient.getInstance().player.sendMessage(
                    Text.literal("§cInvalid motion blur strength! Value must be under 1000."),
                    false
            );
            return 0;
        }

        ConfigManager.getConfig().motionBlurStrength = strength;
        ConfigManager.saveConfig();
        ShaderManager.updateBlurStrength(strength);
        assert MinecraftClient.getInstance().player != null;
        MinecraftClient.getInstance().player.sendMessage(
                Text.literal("Motion blur strength set to " + strength),
                false
        );
        return 1;
    }
}
