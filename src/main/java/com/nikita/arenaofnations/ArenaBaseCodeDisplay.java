package com.nikita.arenaofnations;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Legacy ArmorStand base-code labels — spawn disabled; clear/hide kept for cleanup.
 * Active labels come from client {@code ArenaBaseMarkerRenderer}.
 */
public final class ArenaBaseCodeDisplay {
	private static final Map<Integer, UUID> SLOT_ENTITY_IDS = new HashMap<>();

	private ArenaBaseCodeDisplay() {
	}

	public static void showForSlot(ServerLevel level, BlockPos arenaCenter, int slot, Country country) {
		hideSlot(level, slot);
	}

	public static void hideSlot(ServerLevel level, int slot) {
		UUID existing = SLOT_ENTITY_IDS.remove(slot);
		if (existing == null || level == null) {
			return;
		}
		var entity = level.getEntity(existing);
		if (entity != null) {
			entity.discard();
		}
	}

	public static void clearAll(ServerLevel level, BlockPos arenaCenter) {
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			hideSlot(level, slot);
		}
		if (level != null && arenaCenter != null) {
			ArenaWorldCleanup.removeArenaEntities(level, arenaCenter);
		} else {
			SLOT_ENTITY_IDS.clear();
		}
	}

	public static void clearTracking() {
		SLOT_ENTITY_IDS.clear();
	}

	public static void refreshActiveCountries(ServerLevel level, BlockPos arenaCenter) {
		clearAll(level, arenaCenter);
	}
}
