package com.nikita.arenaofnations;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shared rule for large base-flag / country-name visibility in the Minecraft world.
 * Source of truth: current-round participant + not finally eliminated + assigned base slot.
 * Not derived from living fighters, core HP, reserve, or core protection.
 *
 * <p>RESCUE keeps the label (country can still restore). Final ELIMINATION hides flag and name together.
 */
public final class ArenaBaseFlagVisibility {
	public static final String LABEL_SOURCE = "CURRENT_ROUND_PARTICIPANTS";

	private ArenaBaseFlagVisibility() {
	}

	public static boolean shouldShow(boolean participant, boolean eliminated, int baseSlot) {
		return participant && !eliminated && baseSlot >= 0;
	}

	/**
	 * Snapshot rows are current-round participants (eliminated contenders may still be listed
	 * during BATTLE for HUD bookkeeping, but {@code eliminated=true} hides flag+name).
	 */
	public static boolean shouldShow(ArenaHudCountryState row) {
		if (row == null) {
			return false;
		}
		return shouldShow(true, row.eliminated(), row.baseSlot());
	}

	/** Same gate as the flag — name is never shown without the base flag. */
	public static boolean shouldShowCountryLabel(ArenaHudCountryState row) {
		return shouldShow(row);
	}

	/**
	 * Explicit lifecycle check used by tests/diagnostics.
	 * {@code rescuing} does not hide the label; only final {@code eliminated} does.
	 * {@code livingFighters}/{@code reserve} are accepted so callers do not invent fighter-based gates.
	 */
	public static boolean shouldShowCountryLabel(
			boolean roundParticipant,
			boolean eliminated,
			boolean rescuing,
			int baseSlot,
			int livingFighters,
			int reserve) {
		// Rescue keeps the label. Living/reserve never gate visibility.
		if (rescuing && eliminated) {
			// Impossible combination in production; prefer eliminated.
			return false;
		}
		if (livingFighters < 0 || reserve < 0) {
			return false;
		}
		return shouldShow(roundParticipant, eliminated, baseSlot);
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

	public static int countVisibleLabels(Iterable<ArenaHudCountryState> rows) {
		int count = 0;
		if (rows == null) {
			return 0;
		}
		for (ArenaHudCountryState row : rows) {
			if (shouldShowCountryLabel(row)) {
				count++;
			}
		}
		return count;
	}

	public static int countDuplicateVisibleLabels(Iterable<ArenaHudCountryState> rows) {
		if (rows == null) {
			return 0;
		}
		Set<String> seen = new HashSet<>();
		int duplicates = 0;
		for (ArenaHudCountryState row : rows) {
			if (!shouldShowCountryLabel(row) || row.country() == null) {
				continue;
			}
			String id = row.country().getId();
			if (!seen.add(id)) {
				duplicates++;
			}
		}
		return duplicates;
	}

	public static boolean anyWaitingHolderVisible(ArenaHudSnapshot snapshot) {
		if (snapshot == null || snapshot.state() != ArenaMatchState.WAITING_FOR_OPPONENT) {
			return false;
		}
		return countVisibleLabels(snapshot.countries()) >= 1;
	}

	public static boolean anyRescueLabelVisible(Iterable<ArenaHudCountryState> rows) {
		if (rows == null) {
			return false;
		}
		for (ArenaHudCountryState row : rows) {
			if (row != null && row.rescuing() && shouldShowCountryLabel(row)) {
				return true;
			}
		}
		return false;
	}

	public static boolean anyEliminatedLabelVisible(Iterable<ArenaHudCountryState> rows) {
		if (rows == null) {
			return false;
		}
		for (ArenaHudCountryState row : rows) {
			if (row != null && row.eliminated() && shouldShowCountryLabel(row)) {
				return true;
			}
		}
		return false;
	}

	public static String formatDiagnostics(ArenaHudSnapshot snapshot) {
		Iterable<ArenaHudCountryState> rows = snapshot == null ? java.util.List.of() : snapshot.countries();
		int labels = countVisibleLabels(rows);
		boolean waitingIncluded = snapshot != null
				&& snapshot.state() == ArenaMatchState.WAITING_FOR_OPPONENT
				&& labels >= 1;
		return "baseCountryLabels=" + labels + '\n'
				+ "baseCountryLabelSource=" + LABEL_SOURCE + '\n'
				+ "baseCountryLabelsWaitingIncluded=" + waitingIncluded + '\n'
				+ "baseCountryLabelsRescueIncluded=true\n"
				+ "baseCountryLabelsEliminatedIncluded=false\n"
				+ "baseCountryLabelDuplicates=" + countDuplicateVisibleLabels(rows);
	}

	public static String formatCompact(ArenaHudSnapshot snapshot) {
		return formatDiagnostics(snapshot).replace('\n', ' ').trim().toLowerCase(Locale.ROOT);
	}
}
