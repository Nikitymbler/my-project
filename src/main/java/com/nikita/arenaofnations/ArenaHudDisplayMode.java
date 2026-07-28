package com.nikita.arenaofnations;

import java.util.Locale;

public enum ArenaHudDisplayMode {
	EXTERNAL,
	MINIMAL,
	FULL,
	OFF;

	public static ArenaHudDisplayMode parse(String raw, ArenaHudDisplayMode fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return ArenaHudDisplayMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			return fallback;
		}
	}
}
