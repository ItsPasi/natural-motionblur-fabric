package net.natural.motionblur;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.util.Identifier;
import net.natural.motionblur.config.CommandManager;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.config.EventManager;
import net.natural.motionblur.config.KeybindingManager;

public class MotionBlurMod implements ClientModInitializer {
    public static final String ID = "naturalmotionblur";

    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();
        KeybindingManager.registerKeybindings();
        CommandManager.registerCommands();
        ShaderManager.registerShaderCallbacks();
        EventManager.registerEvents();
    }

    public static Identifier createIdentifier(String path) {
        return Identifier.of(ID, path);
    }
}