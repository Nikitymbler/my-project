package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Static checks for TikTok overlay reconnect loop (single controlled poll, backoff caps).
 */
class ArenaOverlayReconnectScriptTest {
	@Test
	void tiktokJsUsesSingleTimeoutLoopAndBackoffCap() throws Exception {
		Path js = Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		String source = Files.readString(js, StandardCharsets.UTF_8);
		assertTrue(source.contains("pollOnce"), "must use pollOnce");
		assertTrue(source.contains("BACKOFF_MAX_MS = 10000"), "backoff max 10s");
		assertTrue(source.contains("AbortController"), "must abort in-flight requests");
		assertTrue(source.contains("lastSuccessfulData"), "must keep last successful snapshot");
		assertTrue(!source.contains("setInterval(poll"), "must not use setInterval(poll)");
		assertEquals(true, source.contains("setTimeout(pollOnce"));
		assertTrue(source.contains("/arena/overlay-state"));
	}
}
