package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Lifecycle contract for country names above active base flags.
 */
class ArenaBaseCountryLabelLifecycleTest {
	private static ArenaHudCountryState row(
			Country country,
			int slot,
			boolean eliminated,
			boolean rescuing,
			int living,
			int reserve) {
		return new ArenaHudCountryState(
				country,
				slot,
				living,
				rescuing ? 0.0F : 1000.0F,
				1000.0F,
				reserve,
				eliminated,
				rescuing,
				rescuing ? 12 : 0,
				false,
				living > 0);
	}

	@Test
	void waitingSingleCountryShowsOneLabel() {
		List<ArenaHudCountryState> rows = List.of(row(Country.RU, 0, false, false, 0, 4));
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(rows.getFirst()));
		assertEquals(1, ArenaBaseFlagVisibility.countVisibleLabels(rows));
		assertEquals(0, ArenaBaseFlagVisibility.countDuplicateVisibleLabels(rows));
		// living=0 / reserve only must not hide
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(
				true, false, false, 0, 0, 4));
	}

	@Test
	void battleTwoCountriesShowTwoLabels() {
		List<ArenaHudCountryState> rows = List.of(
				row(Country.RU, 0, false, false, 2, 2),
				row(Country.UA, 1, false, false, 2, 2));
		assertEquals(2, ArenaBaseFlagVisibility.countVisibleLabels(rows));
	}

	@Test
	void rescueKeepsLabelEvenWithZeroLivingAndZeroReserve() {
		ArenaHudCountryState rescue = row(Country.RU, 0, false, true, 0, 0);
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(rescue));
		assertTrue(ArenaBaseFlagVisibility.anyRescueLabelVisible(List.of(rescue)));
		assertFalse(ArenaBaseFlagVisibility.anyEliminatedLabelVisible(List.of(rescue)));
	}

	@Test
	void restorePathDoesNotDropLabelWhileNotEliminated() {
		ArenaHudCountryState restored = row(Country.RU, 0, false, false, 1, 0);
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(restored));
	}

	@Test
	void eliminationHidesLabelWhilePeerRemains() {
		List<ArenaHudCountryState> rows = List.of(
				row(Country.RU, 0, true, false, 0, 0),
				row(Country.UA, 1, false, false, 3, 1));
		assertFalse(ArenaBaseFlagVisibility.shouldShowCountryLabel(rows.get(0)));
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(rows.get(1)));
		assertEquals(1, ArenaBaseFlagVisibility.countVisibleLabels(rows));
		assertFalse(ArenaBaseFlagVisibility.anyEliminatedLabelVisible(rows));
	}

	@Test
	void inactiveCountriesNeverShowLabels() {
		assertFalse(ArenaBaseFlagVisibility.shouldShow(false, false, 0));
		assertFalse(ArenaBaseFlagVisibility.shouldShow(true, false, -1));
		assertEquals(0, ArenaBaseFlagVisibility.countVisibleLabels(List.of()));
	}

	@Test
	void resetIdleSnapshotMeansZeroLabels() {
		assertEquals(0, ArenaBaseFlagVisibility.countVisibleLabels(ArenaHudSnapshot.EMPTY.countries()));
		String diag = ArenaBaseFlagVisibility.formatDiagnostics(ArenaHudSnapshot.EMPTY);
		assertTrue(diag.contains("baseCountryLabels=0"));
		assertTrue(diag.contains("baseCountryLabelSource=CURRENT_ROUND_PARTICIPANTS"));
		assertTrue(diag.contains("baseCountryLabelsEliminatedIncluded=false"));
		assertTrue(diag.contains("baseCountryLabelDuplicates=0"));
	}

	@Test
	void diagnosticsMarkWaitingIncluded() {
		ArenaHudSnapshot waiting = new ArenaHudSnapshot(
				ArenaMatchState.WAITING_FOR_OPPONENT,
				400,
				ArenaHudDisplayMode.EXTERNAL,
				1,
				null,
				0,
				64,
				0,
				true,
				List.of(row(Country.RU, 0, false, false, 0, 4)));
		assertTrue(ArenaBaseFlagVisibility.anyWaitingHolderVisible(waiting));
		String diag = ArenaBaseFlagVisibility.formatDiagnostics(waiting);
		assertTrue(diag.contains("baseCountryLabels=1"));
		assertTrue(diag.contains("baseCountryLabelsWaitingIncluded=true"));
		assertTrue(diag.contains("baseCountryLabelsRescueIncluded=true"));
	}

	@Test
	void rendererKeepsSharedVisibilityGate() throws Exception {
		String source = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaBaseMarkerRenderer.java"),
				StandardCharsets.UTF_8);
		assertTrue(source.contains("ArenaBaseFlagVisibility.shouldShow(row)"));
		assertTrue(source.contains("getDisplayName()"));
		assertFalse(source.contains("Country.values()"));
	}

	@Test
	void giftAndResetPushHudSnapshotForImmediateLabels() throws Exception {
		String match = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchManager.java"),
				StandardCharsets.UTF_8);
		assertTrue(match.contains("ArenaRoundHudSync.pushNow(server)"));
		// Both gift end and reset must push HUD (base markers), not only overlay.
		int pushes = match.split("ArenaRoundHudSync\\.pushNow\\(server\\)", -1).length - 1;
		assertTrue(pushes >= 2, "expected pushNow on gift and reset, found " + pushes);
	}
}
