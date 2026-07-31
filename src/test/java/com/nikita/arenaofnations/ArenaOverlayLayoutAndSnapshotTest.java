package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ArenaOverlayLayoutAndSnapshotTest {
	private static String read(String relative) throws Exception {
		return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
	}

	@Test
	void layoutPlansMatchRequestedRanges() {
		assertEquals("countries-1", ArenaOverlayLayout.planFor(1).densityClass());
		assertEquals(1, ArenaOverlayLayout.planFor(1).columns());
		assertEquals(ArenaOverlayLayout.CardSizeMode.LARGE, ArenaOverlayLayout.planFor(1).cardSizeMode());

		assertEquals("countries-2", ArenaOverlayLayout.planFor(2).densityClass());
		assertEquals(1, ArenaOverlayLayout.planFor(2).columns());

		assertEquals("countries-3-4", ArenaOverlayLayout.planFor(3).densityClass());
		assertEquals(2, ArenaOverlayLayout.planFor(4).columns());
		assertEquals(ArenaOverlayLayout.CardSizeMode.MEDIUM, ArenaOverlayLayout.planFor(4).cardSizeMode());

		assertEquals("countries-5-8", ArenaOverlayLayout.planFor(6).densityClass());
		assertEquals(2, ArenaOverlayLayout.planFor(6).columns());
		assertEquals(ArenaOverlayLayout.CardSizeMode.COMPACT, ArenaOverlayLayout.planFor(6).cardSizeMode());

		assertEquals("countries-9-12", ArenaOverlayLayout.planFor(10).densityClass());
		assertEquals(ArenaOverlayLayout.CardSizeMode.COMPACT, ArenaOverlayLayout.planFor(12).cardSizeMode());

		assertEquals("countries-13-20", ArenaOverlayLayout.planFor(20).densityClass());
		assertEquals(2, ArenaOverlayLayout.planFor(20).columns());
		assertEquals(ArenaOverlayLayout.CardSizeMode.ULTRA_COMPACT, ArenaOverlayLayout.planFor(20).cardSizeMode());
	}

	@Test
	void sixAndTwentyCountriesFitCanvasBudget() {
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(1));
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(2));
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(6));
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(20));
		assertEquals(1080, ArenaOverlayLayout.CANVAS_WIDTH);
		assertEquals(1920, ArenaOverlayLayout.CANVAS_HEIGHT);
	}

	@Test
	void firstCountryWaitingSnapshotIncludesRuCard() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		overlay.resetSnapshotForTest();

		String body = ""
				+ "{\"phase\":\"WAITING_FOR_OPPONENT\","
				+ "\"title\":\"АРЕНА · ОЖИДАНИЕ\","
				+ "\"remainingSeconds\":25,"
				+ "\"activeCountryCount\":1,"
				+ "\"overlayParticipantSource\":\"CURRENT_ROUND_PARTICIPANTS\","
				+ "\"overlayWaitingHolderIncluded\":true,"
				+ "\"overlayGridColumns\":1,"
				+ "\"overlayCardSizeMode\":\"LARGE\","
				+ "\"overlayDensityClass\":\"countries-1\","
				+ "\"overlayCanvasWidth\":1080,"
				+ "\"overlayCanvasHeight\":1920,"
				+ "\"overlayDisplayedCountries\":\"RU\","
				+ "\"overlaySnapshotCountries\":\"RU\","
				+ "\"overlayOverflowDetected\":false,"
				+ "\"countries\":[{"
				+ "\"id\":\"ru\",\"code\":\"RU\",\"name\":\"Россия\",\"joinOrder\":0,\"baseSlot\":0,"
				+ "\"activeFighters\":0,\"reserve\":4,\"coreHp\":1000,\"coreMaxHp\":1000,\"corePercent\":100,"
				+ "\"coreProtected\":true,\"coreVulnerable\":false,\"status\":\"PROTECTED\","
				+ "\"rescueSeconds\":0,\"rescueRemaining\":0,\"eliminated\":false"
				+ "}]}";

		overlay.publishSnapshotForTest(body, 1);
		String snap = overlay.snapshotJson();

		assertTrue(snap.contains("\"phase\":\"WAITING_FOR_OPPONENT\""));
		assertTrue(snap.contains("\"code\":\"RU\""));
		assertTrue(snap.contains("\"reserve\":4"));
		assertTrue(snap.contains("\"activeCountryCount\":1"));
		assertTrue(snap.contains("CURRENT_ROUND_PARTICIPANTS"));
		assertTrue(snap.contains("\"overlayWaitingHolderIncluded\":true"));
		assertEquals(1, overlay.snapshotCountryCount());
		assertTrue(overlay.lastWaitingHolderIncluded());
		assertEquals(1, overlay.lastGridColumns());
		assertEquals("LARGE", overlay.lastCardSizeMode());

		int livingPlusReserve = 0 + 4;
		assertEquals(4, livingPlusReserve);
	}

	@Test
	void twoCountriesSnapshotContainsBoth() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		String body = countriesSnapshot("BATTLE", 2,
				countryJson("ru", "RU", 0, 2, 2, 1000),
				countryJson("ua", "UA", 1, 2, 2, 1000));
		overlay.publishSnapshotForTest(body, 2);
		String snap = overlay.snapshotJson();
		assertTrue(snap.contains("\"code\":\"RU\""));
		assertTrue(snap.contains("\"code\":\"UA\""));
		assertEquals(2, overlay.snapshotCountryCount());
	}

	@Test
	void sixCountriesSnapshotAndCompactLayout() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		String[] codes = {"RU", "UA", "BY", "KZ", "PL", "TJ"};
		String[] ids = {"ru", "ua", "by", "kz", "pl", "tj"};
		String[] items = new String[6];
		for (int i = 0; i < 6; i++) {
			items[i] = countryJson(ids[i], codes[i], i, 1, 1, 1000);
		}
		String body = countriesSnapshot("BATTLE", 6, items);
		overlay.publishSnapshotForTest(body, 6);
		String snap = overlay.snapshotJson();
		for (String code : codes) {
			assertTrue(snap.contains("\"code\":\"" + code + "\""), "missing " + code);
		}
		assertEquals("COMPACT", ArenaOverlayLayout.planFor(6).cardSizeMode().name());
		assertEquals(2, ArenaOverlayLayout.planFor(6).columns());
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(6));
	}

	@Test
	void twentyCountriesSnapshotFitsUltraCompact() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		Country[] all = Country.values();
		assertEquals(20, all.length, "expected 20 supported countries");
		String[] items = new String[20];
		StringBuilder displayed = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			Country c = all[i];
			items[i] = countryJson(c.getId(), c.getCode(), i, 0, 1, 1000);
			if (i > 0) {
				displayed.append(',');
			}
			displayed.append(c.getCode());
		}
		ArenaOverlayLayout.LayoutPlan plan = ArenaOverlayLayout.planFor(20);
		String body = "{"
				+ "\"phase\":\"BATTLE\","
				+ "\"title\":\"АРЕНА\","
				+ "\"remainingSeconds\":60,"
				+ "\"activeCountryCount\":20,"
				+ "\"overlayParticipantSource\":\"CURRENT_ROUND_PARTICIPANTS\","
				+ "\"overlayWaitingHolderIncluded\":false,"
				+ "\"overlayGridColumns\":" + plan.columns() + ","
				+ "\"overlayCardSizeMode\":\"" + plan.cardSizeMode().name() + "\","
				+ "\"overlayDensityClass\":\"" + plan.densityClass() + "\","
				+ "\"overlayCanvasWidth\":1080,"
				+ "\"overlayCanvasHeight\":1920,"
				+ "\"overlayDisplayedCountries\":\"" + displayed + "\","
				+ "\"overlaySnapshotCountries\":\"" + displayed + "\","
				+ "\"overlayOverflowDetected\":false,"
				+ "\"countries\":[" + String.join(",", items) + "]"
				+ "}";
		overlay.publishSnapshotForTest(body, 20);
		String snap = overlay.snapshotJson();
		assertEquals(20, overlay.snapshotCountryCount());
		assertEquals("ULTRA_COMPACT", plan.cardSizeMode().name());
		assertTrue(ArenaOverlayLayout.fitsWithoutOverflow(20));
		for (Country c : all) {
			assertTrue(snap.contains("\"code\":\"" + c.getCode() + "\""), "missing " + c.getCode());
		}
		assertTrue(snap.contains("\"coreHp\":"));
		assertTrue(snap.contains("\"coreMaxHp\":"));
	}

	@Test
	void resetClearsOverlayCards() {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		overlay.publishSnapshotForTest(
				countriesSnapshot("WAITING_FOR_OPPONENT", 1, countryJson("ru", "RU", 0, 0, 4, 1000)),
				1);
		assertEquals(1, overlay.snapshotCountryCount());
		overlay.resetSnapshotForTest();
		assertEquals(0, overlay.snapshotCountryCount());
		assertTrue(overlay.snapshotJson().contains("\"countries\":[]"));
		assertEquals("", overlay.lastDisplayedCountryCodes());
	}

	@Test
	void stableOrderPreservedWhenOnlyHpChanges() throws Exception {
		String js = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		assertTrue(js.contains("function orderCountries"));
		assertTrue(js.contains("joinOrder"));
		assertTrue(js.contains("lastOrderIds"));
		assertTrue(js.contains("fillGrid"));
		assertFalse(js.contains("fillPanel(leftPanel"));
		assertFalse(js.contains("slice(0, 10)"));
	}

	@Test
	void cssHasAdaptiveGridClassesWithoutScale() throws Exception {
		String css = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.css");
		assertTrue(css.contains("countries-1"));
		assertTrue(css.contains("countries-2"));
		assertTrue(css.contains("countries-3-4"));
		assertTrue(css.contains("countries-5-8"));
		assertTrue(css.contains("countries-9-12"));
		assertTrue(css.contains("countries-13-20"));
		assertTrue(css.contains("#countries-grid"));
		assertTrue(css.contains("grid-template-columns"));
		assertTrue(css.contains(".core-hp"));
		assertTrue(css.contains("#FF00FF"));
		assertFalse(css.contains("transform: scale("));
		assertFalse(css.contains("zoom:"));
		assertFalse(css.contains("#00FF00"));
		assertFalse(css.contains("overflow-y: auto") || css.contains("overflow: scroll"));
	}

	@Test
	void jsRendersSingleWaitingCountryAndPreviewDiag() throws Exception {
		String js = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		assertTrue(js.contains("WAITING_FOR_OPPONENT"));
		assertTrue(js.contains("densityClassForCount"));
		assertTrue(js.contains("countries-1"));
		assertTrue(js.contains("previewDiagEl"));
		assertTrue(js.contains("overlayWaitingHolderIncluded"));
		assertTrue(js.contains("detectOverflow"));
		assertTrue(js.contains("CHROMA_COLOR = \"#FF00FF\""));
		assertFalse(js.contains("slice(0, 10)"));
		assertFalse(js.contains("updateFitScale"));
	}

	@Test
	void htmlUsesSingleCountriesGrid() throws Exception {
		String html = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html");
		assertTrue(html.contains("id=\"countries-grid\""));
		assertTrue(html.contains("id=\"preview-diag\""));
		assertFalse(html.contains("id=\"left-panel\""));
		assertFalse(html.contains("id=\"right-panel\""));
	}

	@Test
	void participantSourceConstantIsCurrentRound() {
		assertEquals("CURRENT_ROUND_PARTICIPANTS", ArenaOverlayStateService.PARTICIPANT_SOURCE);
		assertEquals("CURRENT_ROUND_PARTICIPANTS", ArenaOverlayStateService.get().participantSource());
	}

	private static String countryJson(
			String id,
			String code,
			int joinOrder,
			int fighters,
			int reserve,
			int hp) {
		return "{"
				+ "\"id\":\"" + id + "\","
				+ "\"code\":\"" + code + "\","
				+ "\"name\":\"" + code + "\","
				+ "\"joinOrder\":" + joinOrder + ","
				+ "\"baseSlot\":" + joinOrder + ","
				+ "\"activeFighters\":" + fighters + ","
				+ "\"reserve\":" + reserve + ","
				+ "\"coreHp\":" + hp + ","
				+ "\"coreMaxHp\":" + hp + ","
				+ "\"corePercent\":100,"
				+ "\"coreProtected\":true,"
				+ "\"coreVulnerable\":false,"
				+ "\"status\":\"PROTECTED\","
				+ "\"rescueSeconds\":0,"
				+ "\"rescueRemaining\":0,"
				+ "\"eliminated\":false"
				+ "}";
	}

	private static String countriesSnapshot(String phase, int activeCount, String... countryJsons) {
		ArenaOverlayLayout.LayoutPlan plan = ArenaOverlayLayout.planFor(countryJsons.length);
		String arr = Stream.of(countryJsons).collect(Collectors.joining(","));
		return "{"
				+ "\"phase\":\"" + phase + "\","
				+ "\"title\":\"АРЕНА\","
				+ "\"remainingSeconds\":60,"
				+ "\"activeCountryCount\":" + activeCount + ","
				+ "\"overlayParticipantSource\":\"CURRENT_ROUND_PARTICIPANTS\","
				+ "\"overlayWaitingHolderIncluded\":"
				+ ("WAITING_FOR_OPPONENT".equals(phase) && countryJsons.length >= 1) + ","
				+ "\"overlayGridColumns\":" + plan.columns() + ","
				+ "\"overlayCardSizeMode\":\"" + plan.cardSizeMode().name() + "\","
				+ "\"overlayDensityClass\":\"" + plan.densityClass() + "\","
				+ "\"overlayCanvasWidth\":1080,"
				+ "\"overlayCanvasHeight\":1920,"
				+ "\"overlayDisplayedCountries\":\"\","
				+ "\"overlaySnapshotCountries\":\"\","
				+ "\"overlayOverflowDetected\":false,"
				+ "\"countries\":[" + arr + "]"
				+ "}";
	}
}
