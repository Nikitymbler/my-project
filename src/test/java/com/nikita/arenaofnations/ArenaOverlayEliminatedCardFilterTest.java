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
 * Browser overlay must drop eliminated cards immediately while keeping RESCUE cards.
 */
class ArenaOverlayEliminatedCardFilterTest {
	private static String read(String relative) throws Exception {
		return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
	}

	@Test
	void overlayServiceFiltersEliminatedFromCountriesArray() throws Exception {
		String source = read("src/main/java/com/nikita/arenaofnations/ArenaOverlayStateService.java");
		assertTrue(source.contains("collectDisplayedOverlayCountries"));
		assertTrue(source.contains("collectEliminatedOverlayCountries"));
		assertTrue(source.contains("pushNowAfterElimination"));
		assertTrue(source.contains("overlayEliminatedCardsVisible"));
		assertTrue(source.contains("overlayDisplayedCountryCount"));
		assertFalse(source.contains("Keep eliminated round participants visible through result display"));
	}

	@Test
	void jsFiltersEliminatedAndRebuildsDensity() throws Exception {
		String js = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		assertTrue(js.contains("c.eliminated === true || c.status === \"ELIMINATED\""));
		assertTrue(js.contains("applyDensityClass(countries.length)"));
		assertTrue(js.contains("!next.has(id)"));
		assertTrue(js.contains("ВЫБЫЛА"));
	}

	@Test
	void rescuePushUsesHudSyncWhileEliminationUsesDedicatedPush() throws Exception {
		String rescue = read("src/main/java/com/nikita/arenaofnations/ArenaCoreRescueManager.java");
		assertTrue(rescue.contains("pushNowAfterElimination(server, country)"));
		assertTrue(rescue.contains("ArenaRoundHudSync.pushNow(server)"));
		String sync = read("src/main/java/com/nikita/arenaofnations/ArenaRoundHudSync.java");
		assertTrue(sync.contains("pushNowAfterElimination"));
		assertTrue(sync.contains("ArenaOverlayStateService.pushNowAfterElimination"));
	}

	@Test
	void publishedSnapshotOmitsEliminatedCardAndUpdatesCounter() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		String battleTwo = ""
				+ "{\"phase\":\"BATTLE\",\"activeCountryCount\":2,"
				+ "\"overlayDisplayedCountryCount\":2,"
				+ "\"overlayEliminatedCardsVisible\":0,"
				+ "\"overlayDisplayedCountries\":\"RU,UA\","
				+ "\"countries\":["
				+ country("ru", "RU", 0, false, false) + ","
				+ country("ua", "UA", 1, false, false)
				+ "]}";
		overlay.publishSnapshotForTest(battleTwo, 2);
		assertTrue(overlay.snapshotJson().contains("\"code\":\"RU\""));
		assertTrue(overlay.snapshotJson().contains("\"code\":\"UA\""));
		assertEquals(2, overlay.snapshotCountryCount());

		String afterElim = ""
				+ "{\"phase\":\"BATTLE\",\"activeCountryCount\":1,"
				+ "\"overlayDisplayedCountryCount\":1,"
				+ "\"overlayEliminatedCardsVisible\":0,"
				+ "\"overlayRoundParticipants\":\"RU,UA\","
				+ "\"overlayEliminatedCountries\":\"RU\","
				+ "\"overlayDisplayedCountries\":\"UA\","
				+ "\"overlayLastRemovedCountry\":\"RU\","
				+ "\"countries\":["
				+ country("ua", "UA", 0, false, false)
				+ "]}";
		overlay.publishSnapshotForTest(afterElim, 1);
		String snap = overlay.snapshotJson();
		assertTrue(snap.contains("\"code\":\"UA\""));
		assertFalse(snap.contains("\"code\":\"RU\""));
		assertTrue(snap.contains("\"overlayEliminatedCardsVisible\":0"));
		assertEquals(1, overlay.snapshotCountryCount());
		assertEquals(1, ArenaOverlayLayout.planFor(1).columns());
	}

	@Test
	void rescueCardRemainsWhileNotEliminated() {
		String rescueBody = ""
				+ "{\"phase\":\"BATTLE\",\"activeCountryCount\":2,"
				+ "\"overlayDisplayedCountryCount\":2,"
				+ "\"countries\":["
				+ country("ru", "RU", 0, false, true) + ","
				+ country("ua", "UA", 1, false, false)
				+ "]}";
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		overlay.publishSnapshotForTest(rescueBody, 2);
		String snap = overlay.snapshotJson();
		assertTrue(snap.contains("\"code\":\"RU\""));
		assertTrue(snap.contains("\"status\":\"RESCUE\"") || snap.contains("\"rescuing\"") || snap.contains("RESCUE"));
		assertEquals(2, overlay.snapshotCountryCount());
	}

	@Test
	void resetClearsDisplayedCards() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		overlay.publishSnapshotForTest(
				"{\"phase\":\"BATTLE\",\"countries\":[" + country("ua", "UA", 0, false, false) + "]}",
				1);
		overlay.resetSnapshotForTest();
		assertEquals(0, overlay.snapshotCountryCount());
		assertTrue(overlay.snapshotJson().contains("\"countries\":[]"));
	}

	@Test
	void layoutModeRecalculatesAfterOneOfSixEliminated() {
		assertEquals("countries-5-8", ArenaOverlayLayout.planFor(6).densityClass());
		assertEquals("countries-5-8", ArenaOverlayLayout.planFor(5).densityClass());
		assertEquals(2, ArenaOverlayLayout.planFor(5).columns());
	}

	private static String country(String id, String code, int joinOrder, boolean eliminated, boolean rescue) {
		return "{"
				+ "\"id\":\"" + id + "\","
				+ "\"code\":\"" + code + "\","
				+ "\"name\":\"" + code + "\","
				+ "\"joinOrder\":" + joinOrder + ","
				+ "\"baseSlot\":" + joinOrder + ","
				+ "\"activeFighters\":0,"
				+ "\"reserve\":2,"
				+ "\"coreHp\":" + (rescue ? 0 : 1000) + ","
				+ "\"coreMaxHp\":1000,"
				+ "\"corePercent\":" + (rescue ? 0 : 100) + ","
				+ "\"coreProtected\":false,"
				+ "\"coreVulnerable\":" + (!rescue) + ","
				+ "\"status\":\"" + (eliminated ? "ELIMINATED" : rescue ? "RESCUE" : "VULNERABLE") + "\","
				+ "\"rescueSeconds\":" + (rescue ? 8 : 0) + ","
				+ "\"rescueRemaining\":" + (rescue ? 8 : 0) + ","
				+ "\"eliminated\":" + eliminated
				+ "}";
	}
}
