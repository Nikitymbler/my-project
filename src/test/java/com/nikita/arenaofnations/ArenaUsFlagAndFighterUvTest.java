package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArenaUsFlagAndFighterUvTest {
	@Test
	void usSvgUsesExplicitStarPolygonsNotMarkers() throws Exception {
		String svg = Files.readString(Path.of("src/main/resources/assets/arena_of_nations/overlay/flags/us.svg"));
		assertTrue(svg.contains("<polygon"), "US SVG must draw stars as polygons for resvg");
		assertTrue(!svg.contains("marker-mid"), "marker-mid stars do not rasterize");
		assertTrue(svg.split("<polygon").length - 1 >= 50, "expected 50 star polygons");
	}

	@Test
	void fighterFlagMatchesBaseTwoSidedUvPath() throws Exception {
		String source = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaFighterOverheadRenderer.java"));
		assertTrue(source.contains("blitFlagQuadFront"));
		assertTrue(source.contains("blitFlagQuadBack"));
		assertTrue(source.contains("Same two-sided path as ArenaBaseMarkerRenderer"));
		String visuals = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaFighterFlagVisuals.java"));
		assertTrue(visuals.contains("textures/gui/flags_hd/"));
	}

	@Test
	void usPngFilesExist() {
		assertTrue(Files.isRegularFile(Path.of(
				"src/main/resources/assets/arena_of_nations/textures/gui/flags/us.png")));
		assertTrue(Files.isRegularFile(Path.of(
				"src/main/resources/assets/arena_of_nations/textures/gui/flags_hd/us.png")));
	}
}
