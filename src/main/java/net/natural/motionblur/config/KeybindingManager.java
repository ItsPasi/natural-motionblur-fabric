package net.natural.motionblur.config;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

public class KeybindingManager {
    private static KeyBinding toggleKeybinding;

    public static void registerKeybindings() {
        // Create the keybinding
        toggleKeybinding = new KeyBinding(
                Text.literal("Toggle Motion Blur").getString(),
                ConfigManager.getConfig().getToggleKey().getCode(),
                KeyBinding.Category.MISC);

        // Register tick event to check for keybinding presses
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (toggleKeybinding.wasPressed()) {
                toggleMotionBlur();
            }
        });
    }

    private static void toggleMotionBlur() {
        ConfigEntries config = ConfigManager.getConfig();
        config.enabled = !config.enabled;
        ConfigManager.saveConfig();
    }

    public static void updateKeybinding() {
        toggleKeybinding.setBoundKey(ConfigManager.getConfig().getToggleKey());
        KeyBinding.updateKeysByCode();
    }
}