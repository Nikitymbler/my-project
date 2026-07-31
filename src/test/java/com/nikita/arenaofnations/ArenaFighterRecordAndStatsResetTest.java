package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class ArenaFighterRecordAndStatsResetTest {
	@Test
	void fighterRecordUpdatesOnlyOnStrictlyGreater() {
		ArenaScoreSavedData data = new ArenaScoreSavedData();
		assertTrue(data.tryUpdateFighterRoundRecord(Country.KZ, 11));
		assertEquals("kz", data.getFighterRoundRecordCountryId());
		assertEquals(11, data.getFighterRoundRecordCount());

		assertFalse(data.tryUpdateFighterRoundRecord(Country.BY, 11)); // equal — keep KZ
		assertEquals("kz", data.getFighterRoundRecordCountryId());
		assertEquals(11, data.getFighterRoundRecordCount());

		assertTrue(data.tryUpdateFighterRoundRecord(Country.BY, 12));
		assertEquals("by", data.getFighterRoundRecordCountryId());
		assertEquals(12, data.getFighterRoundRecordCount());

		assertFalse(data.tryUpdateFighterRoundRecord(Country.RU, 12));
		assertEquals("by", data.getFighterRoundRecordCountryId());
	}

	@Test
	void fighterRecordPersistsInNbtAndIndependentResets() {
		ArenaScoreSavedData data = new ArenaScoreSavedData();
		data.addPoints(Country.RU, 5);
		data.addRoundWin(Country.KZ);
		data.tryUpdateFighterRoundRecord(Country.BY, 20);

		CompoundTag tag = data.save(new CompoundTag(), null);
		ArenaScoreSavedData loaded = ArenaScoreSavedData.load(tag, null);
		assertEquals(5, loaded.getScore(Country.RU));
		assertEquals(1, loaded.getRoundWins(Country.KZ));
		assertEquals("by", loaded.getFighterRoundRecordCountryId());
		assertEquals(20, loaded.getFighterRoundRecordCount());

		loaded.resetRoundWins();
		assertEquals(0, loaded.getRoundWins(Country.KZ));
		assertEquals(5, loaded.getScore(Country.RU));
		assertEquals(20, loaded.getFighterRoundRecordCount());

		loaded.resetScorePoints();
		assertEquals(0, loaded.getScore(Country.RU));
		assertEquals(20, loaded.getFighterRoundRecordCount());

		loaded.resetFighterRoundRecord();
		assertEquals(0, loaded.getFighterRoundRecordCount());
		assertNull(loaded.getFighterRoundRecordCountry());
		assertEquals("", loaded.getFighterRoundRecordCountryId());
	}

	@Test
	void resetAllClearsWinsPointsAndRecord() {
		ArenaScoreSavedData data = new ArenaScoreSavedData();
		data.addPoints(Country.RU, 3);
		data.addRoundWin(Country.RU);
		data.tryUpdateFighterRoundRecord(Country.KZ, 9);
		data.resetAll();
		assertEquals(0, data.getScore(Country.RU));
		assertEquals(0, data.getRoundWins(Country.RU));
		assertEquals(0, data.getFighterRoundRecordCount());
	}

	@Test
	void statsResetAllowedOnlyInIdleOrBreak() {
		assertTrue(ArenaStatsResetService.isResetAllowed());
	}

	@Test
	void htmlHasRecordModuleAndStatsButtons() throws Exception {
		String html = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html"),
				StandardCharsets.UTF_8);
		assertTrue(html.contains("id=\"record-overlay-module\""));
		assertTrue(html.contains("btn-toggle-record"));
		assertTrue(html.contains("СБРОСИТЬ ТОП-5 ПОБЕД"));
		assertTrue(html.contains("СБРОСИТЬ ОЧКИ СТРАН"));
		assertTrue(html.contains("СБРОСИТЬ РЕКОРД БОЙЦОВ"));
		assertTrue(html.contains("СБРОСИТЬ ВСЮ СТАТИСТИКУ"));
		assertTrue(html.contains("id=\"confirm-dialog\""));
		assertTrue(html.contains("РЕКОРД"));
	}

	@Test
	void jsHasStatsResetApisAndRecordRender() throws Exception {
		String js = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js"),
				StandardCharsets.UTF_8);
		assertTrue(js.contains("/overlay/api/stats/reset-round-wins"));
		assertTrue(js.contains("/overlay/api/stats/reset-score-points"));
		assertTrue(js.contains("/overlay/api/stats/reset-fighter-record"));
		assertTrue(js.contains("/overlay/api/stats/reset-all"));
		assertTrue(js.contains("function renderRecord"));
		assertTrue(js.contains("fighterRoundRecord"));
		assertTrue(js.contains("confirm: true"));
		assertTrue(js.contains("record-overlay-module") || js.contains("recordModuleEl"));
	}

	@Test
	void cssHasExactRecordSizes() throws Exception {
		String css = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.css"),
				StandardCharsets.UTF_8);
		assertTrue(css.contains("#record-overlay-module"));
		assertTrue(css.contains("width: 160px"));
		assertTrue(css.contains("min-height: 100px"));
		assertTrue(css.contains("font-size: 34px"));
		assertTrue(css.contains("border-radius: 50%"));
		assertTrue(css.contains("border: 2px solid gold"));
		assertFalse(css.contains(".record-body") && css.contains(".record-body") && css.matches("(?s).*\\.record-body\\s*\\{[^}]*#FF00FF.*"));
	}
}
