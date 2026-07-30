package com.nikita.arenaofnations;

/**
 * Shared rule for large base-flag visibility in the Minecraft world.
 * Source of truth: round participant + not finally eliminated + assigned base slot.
 * Not derived from living fighters, core HP, or core protection.
 */
public final class ArenaBaseFlagVisibility {
	private ArenaBaseFlagVisibility() {
	}

	public static boolean shouldShow(boolean participant, boolean eliminated, int baseSlot) {
		return participant && !eliminated && baseSlot >= 0;
	}

	/** Snapshot rows are already round participants (or eliminated contenders still listed). */
	public static boolean shouldShow(ArenaHudCountryState row) {
		if (row == null) {
			return false;
		}
		return shouldShow(true, row.eliminated(), row.baseSlot());
	}

	public static String hideReason(boolean participant, boolean eliminated, int baseSlot) {
		if (!participant) {
			return "not_participant";
		}
		if (baseSlot < 0) {
			return "no_base_slot";
		}
		if (eliminated) {
			return "eliminated";
		}
		return "none";
	}

	public static String hideReason(ArenaHudCountryState row) {
		if (row == null) {
			return "no_row";
		}
		return hideReason(true, row.eliminated(), row.baseSlot());
	}
}
