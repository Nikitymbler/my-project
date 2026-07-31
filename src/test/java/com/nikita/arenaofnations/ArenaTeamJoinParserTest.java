package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArenaTeamJoinParserTest {
	@Test
	void acceptsPrimaryFormatsForAllTwentyCountries() {
		assertEquals(20, Country.SUPPORTED_COUNT);
		for (Country country : Country.ALL) {
			ArenaTeamJoinParser.Result exact = ArenaTeamJoinParser.parse(country.getId());
			assertTrue(exact.matched(), country.getId());
			assertEquals(country, exact.country());
			assertFalse(exact.legacyFormat());

			ArenaTeamJoinParser.Result upper = ArenaTeamJoinParser.parse(country.getCode());
			assertTrue(upper.matched());
			assertEquals(country, upper.country());

			ArenaTeamJoinParser.Result spaced = ArenaTeamJoinParser.parse("  " + country.getId() + "  ");
			assertTrue(spaced.matched());
			assertEquals(country, spaced.country());

			ArenaTeamJoinParser.Result word = ArenaTeamJoinParser.parse("команда " + country.getId());
			assertTrue(word.matched());
			assertEquals(country, word.country());
			assertFalse(word.legacyFormat());

			ArenaTeamJoinParser.Result wordUpper = ArenaTeamJoinParser.parse("КОМАНДА   " + country.getCode());
			assertTrue(wordUpper.matched());
			assertEquals(country, wordUpper.country());

			ArenaTeamJoinParser.Result legacy = ArenaTeamJoinParser.parse("!" + country.getId());
			assertTrue(legacy.matched());
			assertEquals(country, legacy.country());
			assertTrue(legacy.legacyFormat());
		}
	}

	@Test
	void acceptsNaturalRussianTeamWordVariants() {
		assertEquals(Country.RU, ArenaTeamJoinParser.parse("команда ru").country());
		assertEquals(Country.RU, ArenaTeamJoinParser.parse("Команда RU").country());
		assertEquals(Country.UA, ArenaTeamJoinParser.parse("КОМАНДА   ua").country());
	}

	@Test
	void rejectsPartialAndAmbiguousComments() {
		assertFalse(ArenaTeamJoinParser.parse("привет ru").matched());
		assertFalse(ArenaTeamJoinParser.parse("ru вперед").matched());
		assertFalse(ArenaTeamJoinParser.parse("я за ru").matched());
		assertFalse(ArenaTeamJoinParser.parse("true").matched());
		assertFalse(ArenaTeamJoinParser.parse("russian").matched());
		assertFalse(ArenaTeamJoinParser.parse("подарок ru 10").matched());
		assertFalse(ArenaTeamJoinParser.parse("").matched());
		assertFalse(ArenaTeamJoinParser.parse("   ").matched());
		assertFalse(ArenaTeamJoinParser.parse(null).matched());
		assertFalse(ArenaTeamJoinParser.parse("неизвестный").matched());
		assertFalse(ArenaTeamJoinParser.parse("!xx").matched());
		assertFalse(ArenaTeamJoinParser.parse("команда").matched());
		assertFalse(ArenaTeamJoinParser.parse("команда russian").matched());
	}

	@Test
	void normalizeCollapsesSpacesWithoutStrippingWords() {
		assertEquals("команда ru", ArenaTeamJoinParser.normalize("  Команда   RU  "));
		assertEquals("привет ru", ArenaTeamJoinParser.normalize("привет   RU"));
	}

	@Test
	void legacyHelperStillResolvesThroughViewerManager() {
		assertEquals(Country.RU, ArenaViewerEventManager.parseCountryCommand("ru"));
		assertEquals(Country.KZ, ArenaViewerEventManager.parseCountryCommand("!kz"));
		assertNull(ArenaViewerEventManager.parseCountryCommand("привет ru"));
	}

	@Test
	void overlayDoesNotShowTeamCodesHintPanel() throws Exception {
		String html = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html"),
				StandardCharsets.UTF_8);
		assertFalse(html.contains("КОДЫ КОМАНД"));
		assertFalse(html.contains("team-codes-hint"));
		assertFalse(html.contains("team-codes-list"));

		String js = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js"),
				StandardCharsets.UTF_8);
		assertFalse(js.contains("fillTeamCodesHint"));
		assertFalse(js.contains("team-codes-list"));
		assertFalse(js.contains("team-code-chip"));

		String css = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.css"),
				StandardCharsets.UTF_8);
		assertFalse(css.contains("team-codes-hint"));
		assertFalse(css.contains("team-code-chip"));
	}
}
