package com.nikita.arenaofnations;

/**
 * Incoming viewer chat message for the platform-independent event queue.
 */
public record ViewerChatEvent(String viewerId, String viewerName, String message, String eventId) {
}
