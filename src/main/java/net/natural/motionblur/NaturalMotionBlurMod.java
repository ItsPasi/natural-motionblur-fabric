package net.natural.motionblur;

import net.fabricmc.api.ClientModInitializer;
import net.natural.motionblur.config.CommandManager;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.config.EventManager;
import net.natural.motionblur.config.KeybindingManager;

public class NaturalMotionBlurMod implements ClientModInitializer {
    public static final String ID = "naturalmotionblur";

    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();
        KeybindingManager.registerKeybindings();
        CommandManager.registerCommands();
        EventManager.registerEvents();
    }
}