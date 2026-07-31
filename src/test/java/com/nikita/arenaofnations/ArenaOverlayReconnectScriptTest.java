package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		assertTrue(source.contains("const POLL_MS = 750"), "poll interval 750ms");
		assertTrue(source.contains("RECONNECT_BASE_MS = 1000"), "reconnect starts at 1s");
		assertTrue(source.contains("BACKOFF_MAX_MS = 10000"), "backoff max 10s");
		assertTrue(source.contains("AbortController"), "must abort in-flight requests");
		assertTrue(source.contains("lastSuccessfulData"), "must keep last successful snapshot");
		assertTrue(source.contains("RECONNECTING"), "must show reconnecting status");
		assertFalse(source.contains("setInterval("), "must not use setInterval");
		assertEquals(true, source.contains("setTimeout(pollOnce"));
		assertTrue(source.contains("/arena/overlay-state"));

		Matcher pollOnceDefs = Pattern.compile("async function pollOnce\\(").matcher(source);
		int defs = 0;
		while (pollOnceDefs.find()) {
			defs++;
		}
		assertEquals(1, defs, "exactly one pollOnce definition");

		Matcher schedule = Pattern.compile("setTimeout\\(pollOnce").matcher(source);
		int schedules = 0;
		while (schedule.find()) {
			schedules++;
		}
		assertEquals(1, schedules, "exactly one setTimeout(pollOnce) schedule site");
	}
}
