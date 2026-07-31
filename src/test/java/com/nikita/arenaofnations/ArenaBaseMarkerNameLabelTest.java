package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Render-math + source checks for country names above base flags.
 */
class ArenaBaseMarkerNameLabelTest {
	private static String read(String relative) throws Exception {
		return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
	}

	@Test
	void labelWorldYIsAboveFlagTop() {
		double flagCenterY = 70.2D;
		double flagTop = ArenaBaseMarkerLayout.flagTopWorldY(flagCenterY);
		double labelY = ArenaBaseMarkerLayout.labelWorldY(flagCenterY);
		assertEquals(flagCenterY + ArenaBaseMarkerLayout.FLAG_HALF_H, flagTop, 1.0E-6);
		assertEquals(flagTop + ArenaBaseMarkerLayout.NAME_WORLD_GAP, labelY, 1.0E-6);
		assertTrue(ArenaBaseMarkerLayout.labelIsAboveFlag(flagCenterY, labelY));
		assertTrue(ArenaBaseMarkerLayout.labelDiffersFromFlagCenter(flagCenterY, labelY));
		assertFalse(ArenaBaseMarkerLayout.labelIsAboveFlag(flagCenterY, flagCenterY));
	}

	@Test
	void textWidthIsCentered() {
		assertEquals(-40.0F, ArenaBaseMarkerLayout.centeredTextX(80), 1.0E-4F);
		assertEquals(0.0F, ArenaBaseMarkerLayout.centeredTextX(0), 1.0E-4F);
	}

	@Test
	void rendererUsesSeparateWorldBillboardAndSeeThrough() throws Exception {
		String source = read("src/client/java/com/nikita/arenaofnations/client/ArenaBaseMarkerRenderer.java");
		assertTrue(source.contains("renderCountryNameBillboard"));
		assertTrue(source.contains("labelWorldY"));
		assertTrue(source.contains("Font.DisplayMode.SEE_THROUGH"));
		assertTrue(source.contains("LightTexture.FULL_BRIGHT"));
		assertTrue(source.contains("getDisplayName()"));
		assertTrue(source.contains("scale(-NAME_TEXT_SCALE, -NAME_TEXT_SCALE, NAME_TEXT_SCALE)"));
		assertFalse(source.contains("NAME_GAP_ABOVE_FLAG"));
		assertFalse(source.contains("-FLAG_HALF_H - NAME_GAP"));
		assertFalse(source.contains("getDisplayName().toUpperCase"));
	}

	@Test
	void waitingRuPassesVisibilityGateForRenderList() {
		ArenaHudCountryState ru = new ArenaHudCountryState(
				Country.RU, 0, 0, 1000.0F, 1000.0F, 4, false, false, 0, true, false);
		assertTrue(ArenaBaseFlagVisibility.shouldShow(ru));
		assertTrue(ArenaBaseFlagVisibility.shouldShowCountryLabel(ru));
		assertEquals(1, ArenaBaseFlagVisibility.countVisibleLabels(java.util.List.of(ru)));
	}

	@Test
	void countryDisplayNamesAreHumanReadable() {
		for (Country country : Country.values()) {
			assertFalse(country.getDisplayName().equals(country.getCode()));
			assertTrue(country.getDisplayName().length() >= 3);
		}
	}

	@Test
	void legacyTextDisplaySpawnRemainsDisabled() throws Exception {
		String source = read("src/main/java/com/nikita/arenaofnations/ArenaCoreDisplayManager.java");
		assertTrue(source.contains("Legacy TextDisplay labels disabled"));
		String update = source.substring(source.indexOf("public void updateForCountry"), source.indexOf("public void hideSlot"));
		assertFalse(update.contains("spawnOrUpdate"));
	}

	@Test
	void clientStatusExposesLabelDrawDiagnostics() throws Exception {
		String source = read("src/client/java/com/nikita/arenaofnations/client/ArenaBaseMarkerSettings.java");
		assertTrue(source.contains("baseCountryLabelsExpected="));
		assertTrue(source.contains("baseCountryLabelsActuallyDrawn="));
		assertTrue(source.contains("lastLabelWorldPositionRU="));
		assertTrue(source.contains("lastLabelRenderError="));
	}
}
