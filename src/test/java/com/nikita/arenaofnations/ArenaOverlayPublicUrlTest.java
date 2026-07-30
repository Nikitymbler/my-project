package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArenaOverlayPublicUrlTest {
	@Test
	void acceptsHttpsHostnameAndPath() {
		var result = ArenaOverlayPublicUrl.validate("https://arena.example.com", "/overlay/tiktok");
		assertTrue(result.valid());
		assertEquals("https://arena.example.com/overlay/tiktok", result.url());
	}

	@Test
	void rejectsHttpForPublicProduction() {
		var result = ArenaOverlayPublicUrl.validate("http://arena.example.com", "/overlay/tiktok");
		assertFalse(result.valid());
		assertEquals("https_required", result.reason());
	}

	@Test
	void rejectsEmptyHostname() {
		var result = ArenaOverlayPublicUrl.validate("", "/overlay/tiktok");
		assertFalse(result.valid());
		assertEquals("empty_base_url", result.reason());
	}

	@Test
	void rejectsPreviewQuery() {
		var result = ArenaOverlayPublicUrl.validate("https://arena.example.com", "/overlay/tiktok?preview=1");
		assertFalse(result.valid());
		assertEquals("preview_query_not_allowed", result.reason());
	}

	@Test
	void rejectsPathWithoutLeadingSlash() {
		var result = ArenaOverlayPublicUrl.validate("https://arena.example.com", "overlay/tiktok");
		assertFalse(result.valid());
		assertEquals("path_must_start_with_slash", result.reason());
	}

	@Test
	void rejectsDoubleSlashInPath() {
		var result = ArenaOverlayPublicUrl.validate("https://arena.example.com", "/overlay//tiktok");
		assertFalse(result.valid());
		assertEquals("path_double_slash", result.reason());
	}

	@Test
	void stripsTrailingSlashOnBase() {
		var result = ArenaOverlayPublicUrl.validate("https://arena.example.com/", "/overlay/tiktok");
		assertTrue(result.valid());
		assertEquals("https://arena.example.com/overlay/tiktok", result.url());
	}
}
