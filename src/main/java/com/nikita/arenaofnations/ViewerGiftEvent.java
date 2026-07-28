package com.nikita.arenaofnations;

/**
 * Incoming viewer gift for the platform-independent event queue.
 */
public record ViewerGiftEvent(String viewerId, String viewerName, int coins, String eventId) {
}
