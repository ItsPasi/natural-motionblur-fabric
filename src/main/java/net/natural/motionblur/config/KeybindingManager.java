package net.natural.motionblur.config;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

public class KeybindingManager {
    private static KeyMapping toggleKeybinding;

    public static void registerKeybindings() {
        toggleKeybinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "Toggle Motion Blur",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KeyMapping.Category.MISC
        ));

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
        if (config.enabled) {
            net.natural.motionblur.ShaderManager.invalidate();
        }
    }
}