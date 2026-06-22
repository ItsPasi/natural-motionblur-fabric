package net.natural.motionblur;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.natural.motionblur.config.CommandManager;
import net.natural.motionblur.config.ConfigManager;
import net.natural.motionblur.config.EventManager;
import net.natural.motionblur.config.KeybindingManager;
import net.natural.motionblur.recording.RecordingShaderManager;

public class NaturalMotionBlurMod implements ClientModInitializer, ModMenuApi {
    public static final String ID = "naturalmotionblur";

    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();
        KeybindingManager.registerKeybindings();
        CommandManager.registerCommands();
        EventManager.registerEvents();

        Runtime.getRuntime().addShutdownHook(new Thread(RecordingShaderManager::destroyFromAnyThread,
                "nmb-recording-output-cleanup"));
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigManager::createConfigScreen;
    }
}