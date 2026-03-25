package net.natural.motionblur.config;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

public class KeybindingManager {
    private static KeyMapping toggleKeybinding;

    public static void registerKeybindings() {
        // Create the keybinding
        toggleKeybinding = new KeyMapping(
                Component.literal("Toggle Motion Blur").getString(),
                ConfigManager.getConfig().getToggleKey().getValue(),
                KeyMapping.Category.MISC);

        // Register tick event to check for keybinding presses
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (toggleKeybinding.consumeClick()) {
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
        toggleKeybinding.setKey(ConfigManager.getConfig().getToggleKey());
        KeyMapping.resetMapping();
    }
}