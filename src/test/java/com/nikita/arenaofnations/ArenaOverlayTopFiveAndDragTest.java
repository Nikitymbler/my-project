package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class ArenaOverlayTopFiveAndDragTest {
	private static String read(String relative) throws Exception {
		return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
	}

	@Test
	void winsGrammarMatchesRussianRules() {
		assertEquals("1 победа", ArenaRoundWinsGrammar.formatWins(1));
		assertEquals("2 победы", ArenaRoundWinsGrammar.formatWins(2));
		assertEquals("3 победы", ArenaRoundWinsGrammar.formatWins(3));
		assertEquals("4 победы", ArenaRoundWinsGrammar.formatWins(4));
		assertEquals("5 побед", ArenaRoundWinsGrammar.formatWins(5));
		assertEquals("11 побед", ArenaRoundWinsGrammar.formatWins(11));
		assertEquals("21 победа", ArenaRoundWinsGrammar.formatWins(21));
		assertEquals("22 победы", ArenaRoundWinsGrammar.formatWins(22));
		assertEquals("25 побед", ArenaRoundWinsGrammar.formatWins(25));
		assertEquals("124 победы", ArenaRoundWinsGrammar.formatWins(124));
	}

	@Test
	void topFiveRankingOmitsZeroWinsAndCapsAtFive() {
		Map<Country, Integer> wins = ArenaTopCountriesRanking.emptyWinsMap();
		Map<Country, Integer> scores = ArenaTopCountriesRanking.emptyWinsMap();

		assertTrue(ArenaTopCountriesRanking.rank(wins, scores, 5).isEmpty());

		wins.put(Country.KZ, 11);
		wins.put(Country.BY, 3);
		List<ArenaTopCountriesRanking.Entry> two = ArenaTopCountriesRanking.rank(wins, scores, 5);
		assertEquals(2, two.size());
		assertEquals(Country.KZ, two.get(0).country());
		assertEquals(1, two.get(0).rank());
		assertEquals(Country.BY, two.get(1).country());

		wins.put(Country.RU, 1);
		wins.put(Country.TM, 1);
		wins.put(Country.AM, 1);
		wins.put(Country.UA, 1);
		scores.put(Country.RU, 10);
		scores.put(Country.TM, 5);
		scores.put(Country.AM, 5);
		scores.put(Country.UA, 1);

		List<ArenaTopCountriesRanking.Entry> top = ArenaTopCountriesRanking.rank(wins, scores, 5);
		assertEquals(5, top.size());
		assertEquals(Country.KZ, top.get(0).country());
		assertEquals(Country.BY, top.get(1).country());
		assertEquals(Country.RU, top.get(2).country());
		assertEquals(Country.AM, top.get(3).country());
		assertEquals(Country.TM, top.get(4).country());
		assertFalse(top.stream().anyMatch(e -> e.country() == Country.UA));
	}

	@Test
	void scoreSavedDataPersistsRoundWinsSeparatelyFromPoints() {
		ArenaScoreSavedData data = new ArenaScoreSavedData();
		assertEquals(0, data.getRoundWins(Country.RU));
		data.addPoints(Country.RU, 5);
		data.addRoundWin(Country.RU);
		data.addRoundWin(Country.RU);
		assertEquals(5, data.getScore(Country.RU));
		assertEquals(2, data.getRoundWins(Country.RU));

		CompoundTag tag = data.save(new CompoundTag(), null);
		assertTrue(tag.contains("ru"));
		assertEquals(5, tag.getInt("ru"));
		assertTrue(tag.contains(ArenaScoreSavedData.ROUND_WINS_KEY));
		assertEquals(2, tag.getCompound(ArenaScoreSavedData.ROUND_WINS_KEY).getInt("ru"));

		ArenaScoreSavedData loaded = ArenaScoreSavedData.load(tag, null);
		assertEquals(5, loaded.getScore(Country.RU));
		assertEquals(2, loaded.getRoundWins(Country.RU));
		assertEquals(0, loaded.getRoundWins(Country.KZ));

		// Legacy tag without roundWins compound keeps wins at 0.
		CompoundTag legacy = new CompoundTag();
		legacy.putInt("kz", 3);
		ArenaScoreSavedData fromLegacy = ArenaScoreSavedData.load(legacy, null);
		assertEquals(3, fromLegacy.getScore(Country.KZ));
		assertEquals(0, fromLegacy.getRoundWins(Country.KZ));

		data.resetAll();
		assertEquals(0, data.getScore(Country.RU));
		assertEquals(0, data.getRoundWins(Country.RU));
	}

	@Test
	void htmlHasIndependentModulesAndEditToolbar() throws Exception {
		String html = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html");
		assertTrue(html.contains("id=\"battle-overlay-module\""));
		assertTrue(html.contains("id=\"top-five-countries-module\""));
		assertTrue(html.contains("id=\"overlay-workspace\""));
		assertTrue(html.contains("ТОП 5 СТРАН"));
		assertTrue(html.contains("ПОКА НЕТ ПОБЕД"));
		assertTrue(html.contains("id=\"edit-toolbar\""));
		assertTrue(html.contains("СОХРАНИТЬ"));
		assertTrue(html.contains("СБРОСИТЬ ПОЗИЦИИ"));
		assertTrue(html.contains("btn-toggle-battle"));
		assertTrue(html.contains("btn-toggle-top5"));
		assertTrue(html.contains("module-drag-handle"));
		assertFalse(html.contains("id=\"stage-inner\""));
		assertFalse(html.contains("id=\"fit-root\""));
	}

	@Test
	void jsSupportsServerLayoutDragAndTopFive() throws Exception {
		String js = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		assertTrue(js.contains("params.get(\"edit\") === \"1\""));
		assertTrue(js.contains("/overlay/api/layout"));
		assertTrue(js.contains("xRatio"));
		assertTrue(js.contains("yRatio"));
		assertTrue(js.contains("setPointerCapture"));
		assertTrue(js.contains("pointerdown"));
		assertTrue(js.contains("pointermove"));
		assertTrue(js.contains("pointerup"));
		assertTrue(js.contains("function renderTopFive"));
		assertTrue(js.contains("top5-wins-number"));
		assertTrue(js.contains("top5-wins-word"));
		assertTrue(js.contains("function formatWins"));
		assertTrue(js.contains("function winsWord"));
		assertTrue(js.contains("clampFullyInside"));
		assertTrue(js.contains("loadLayoutFromServer"));
		assertTrue(js.contains("ПОЗИЦИЯ СОХРАНЕНА"));
		assertTrue(js.contains("CHROMA_COLOR = \"#FF00FF\""));
		assertTrue(js.contains("topCountries"));
		assertFalse(js.contains("MIN_VISIBLE_PX = 40"));
		assertFalse(js.contains("updateFitScale"));
	}

	@Test
	void cssHasExactTopFiveSizesAndWorkspace() throws Exception {
		String css = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.css");
		assertTrue(css.contains("#overlay-workspace"));
		assertTrue(css.contains("width: max(100vw, 1080px)"));
		assertTrue(css.contains("min-height: max(100vh, 1920px)"));
		assertTrue(css.contains("width: 400px"));
		assertTrue(css.contains("min-height: 72px"));
		assertTrue(css.contains("grid-template-columns: 36px 52px minmax(0, 1fr) 126px"));
		assertTrue(css.contains(".top5-wins-number"));
		assertTrue(css.contains("font-size: 30px"));
		assertTrue(css.contains(".top5-wins-word"));
		assertTrue(css.contains("font-size: 19px"));
		assertTrue(css.contains("position: fixed"));
		assertTrue(css.contains("z-index: 10000"));
		assertTrue(css.contains("#FF00FF"));
		assertFalse(css.contains("transform: scale("));
		assertFalse(css.contains("zoom:"));
	}

	@Test
	void overlayStatusMentionsServerLayout() throws Exception {
		String source = read("src/main/java/com/nikita/arenaofnations/ArenaOverlayCommands.java");
		assertTrue(source.contains("browserOverlayDraggable=true"));
		assertTrue(source.contains("browserOverlayEditModeSupported=true"));
		assertTrue(source.contains("overlayLayoutPersistence=SERVER_CONFIG"));
		assertTrue(source.contains("overlayLayoutStorage=SERVER_CONFIG"));
		assertTrue(source.contains("chromaKeyColor=#FF00FF"));
	}

	@Test
	void scoreManagerAwardsRoundWinWithBattleAndHold() throws Exception {
		String source = read("src/main/java/com/nikita/arenaofnations/ArenaScoreManager.java");
		assertTrue(source.contains("addRoundWin(server, country)"));
		assertTrue(source.contains("ArenaRoundHudSync.pushNow(server)"));
		assertTrue(source.contains("awardBattleWin"));
		assertTrue(source.contains("awardHold"));
	}

	@Test
	void stableSortUsesCountryOrdinalOnTie() {
		Map<Country, Integer> wins = new EnumMap<>(Country.class);
		Map<Country, Integer> scores = new EnumMap<>(Country.class);
		for (Country c : Country.values()) {
			wins.put(c, 0);
			scores.put(c, 0);
		}
		wins.put(Country.AM, 1);
		wins.put(Country.TM, 1);
		scores.put(Country.AM, 1);
		scores.put(Country.TM, 1);
		List<ArenaTopCountriesRanking.Entry> ranked = ArenaTopCountriesRanking.rank(wins, scores, 5);
		assertEquals(2, ranked.size());
		assertTrue(ranked.get(0).country().ordinal() < ranked.get(1).country().ordinal());
	}
}
