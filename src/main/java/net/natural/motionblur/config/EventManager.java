package net.natural.motionblur.config;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class EventManager {

    // Variables for handling delayed error messages
    private static boolean delayMessageSent = false;
    private static int tickCounter = 0;
    private static final int TICK_DELAY = 80; // Delay in ticks before showing messages
    private static List<String> errorMessagesToDisplay = new ArrayList<>();
    private static boolean hasConfigReset = false;

    public static void registerEvents() {

        // Register event to check for config reset and prepare messages
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ConfigManager.isConfigReset()) {
                hasConfigReset = true;
                errorMessagesToDisplay = ConfigManager.getAndClearErrorMessages();
                tickCounter = 0; // Reset the local delay state
                delayMessageSent = false;
            } else {
                hasConfigReset = false;
                errorMessagesToDisplay.clear();
            }
        });

        // Register tick event to display error messages after a delay
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only process if there are messages to display and they haven't been sent yet
            if (hasConfigReset && !delayMessageSent && client.player != null) {
                if (tickCounter >= TICK_DELAY) {
                    // Display accumulated error messages
                    for (String errorMessage : errorMessagesToDisplay) {
                        client.player.sendSystemMessage(Component.literal("§c" + errorMessage));
                    }
                    // Mark messages as sent for this join
                    delayMessageSent = true;
                    errorMessagesToDisplay.clear();
                    hasConfigReset = false;
                } else {
                    tickCounter++;
                }
            }
        });
    }
}