package net.natural.motionblur.config;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class EventManager {

    public static void registerEvents() {
        // Register connection event to display error messages if config was reset
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ConfigManager.isConfigReset()) {
                ConfigManager.resetTickCounter();
                ConfigManager.setDelayMessageSent(false);

                // Register tick event to display error messages after a delay
                ClientTickEvents.END_CLIENT_TICK.register(tickClient -> {
                    if (ConfigManager.getTickCounter() >= ConfigManager.getTickDelay() && !ConfigManager.isDelayMessageSent()) {
                        ConfigManager.displayErrorMessages(client);
                    } else {
                        ConfigManager.incrementTickCounter();
                    }
                });
            }
        });
    }
}