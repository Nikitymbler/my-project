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
	void fighterFlagUvIsFlippedAgainstBillboardMirror() throws Exception {
		String source = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaFighterOverheadRenderer.java"));
		assertTrue(source.contains("U is flipped"));
		// left vertex uses u=1 (hoist after scale(-s,-s,s))
		assertTrue(source.contains("left, bottom, z, 1.0F, 1.0F"));
		assertTrue(source.contains("right, bottom, z, 0.0F, 1.0F"));
	}

	@Test
	void usPngFilesExist() {
		assertTrue(Files.isRegularFile(Path.of(
				"src/main/resources/assets/arena_of_nations/textures/gui/flags/us.png")));
		assertTrue(Files.isRegularFile(Path.of(
				"src/main/resources/assets/arena_of_nations/textures/gui/flags_hd/us.png")));
	}
}
