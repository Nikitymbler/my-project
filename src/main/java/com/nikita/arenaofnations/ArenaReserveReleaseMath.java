package com.nikita.arenaofnations;

/**
 * Pure reserve-wave sizing: how many fighters to release this planned cycle.
 */
public final class ArenaReserveReleaseMath {
	/** Hard cap on living fighters of the waiting team during {@code WAITING_FOR_OPPONENT}. */
	public static final int WAITING_FIELD_LIMIT = 25;

	private ArenaReserveReleaseMath() {
	}

	/**
	 * {@code actualRelease = min(configuredBatch, currentReserve, availableActiveSlots)}.
	 * Non-positive inputs yield 0 (no release, reserve unchanged).
	 */
	public static int computeActualRelease(int configuredBatch, int currentReserve, int availableActiveSlots) {
		return computeActualRelease(configuredBatch, currentReserve, availableActiveSlots, Integer.MAX_VALUE);
	}

	/**
	 * WAITING/BATTLE formula:
	 * {@code actualRelease = min(batch, reserve, availableActiveSlots, waitingRemainingSlots)}.
	 * Pass {@link Integer#MAX_VALUE} for {@code waitingRemainingSlots} during BATTLE
	 * (no waiting-field cap).
	 */
	public static int computeActualRelease(
			int configuredBatch,
			int currentReserve,
			int availableActiveSlots,
			int waitingRemainingSlots) {
		if (configuredBatch <= 0
				|| currentReserve <= 0
				|| availableActiveSlots <= 0
				|| waitingRemainingSlots <= 0) {
			return 0;
		}
		int capped = Math.min(configuredBatch, Math.min(currentReserve, availableActiveSlots));
		if (waitingRemainingSlots == Integer.MAX_VALUE) {
			return capped;
		}
		return Math.min(capped, waitingRemainingSlots);
	}

	public static int availableActiveSlots(int activeFightersLimit, int currentActiveFighters) {
		if (activeFightersLimit <= 0) {
			return 0;
		}
		if (activeFightersLimit == Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return Math.max(0, activeFightersLimit - Math.max(0, currentActiveFighters));
	}

	/**
	 * Slots left under {@link #WAITING_FIELD_LIMIT} for living fighters already on the field.
	 * Counts only current living field entities — not gifts, reserve, or historical sent totals.
	 */
	public static int waitingRemainingSlots(int waitingFieldLimit, int currentWaitingFieldCount) {
		if (waitingFieldLimit <= 0) {
			return 0;
		}
		return Math.max(0, waitingFieldLimit - Math.max(0, currentWaitingFieldCount));
	}

	/**
	 * Simulates successive WAITING waves until the field is full or reserve is empty.
	 * Used by unit tests (no Minecraft entities).
	 */
	public static int[] simulateWaitingWaves(
			int configuredBatch,
			int initialReserve,
			int initialFieldCount,
			int maxWaves) {
		int reserve = Math.max(0, initialReserve);
		int field = Math.max(0, initialFieldCount);
		int[] releases = new int[Math.max(0, maxWaves)];
		for (int i = 0; i < releases.length; i++) {
			int waitingSlots = waitingRemainingSlots(WAITING_FIELD_LIMIT, field);
			int availableSlots = availableActiveSlots(Integer.MAX_VALUE, field);
			int release = computeActualRelease(configuredBatch, reserve, availableSlots, waitingSlots);
			releases[i] = release;
			reserve -= release;
			field += release;
			if (release == 0) {
				break;
			}
		}
		return releases;
	}
}
