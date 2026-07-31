package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ArenaClientPerfOptimizationTest {
	@AfterEach
	void tearDown() {
		ArenaClientPerfConfig.replaceForTest(ArenaClientPerfConfig.defaults());
		System.clearProperty("arena.client.perf.config");
	}

	@Test
	void configLoadsDefaultsAndClamps() throws Exception {
		Path path = Files.createTempFile("arena-client-perf-", ".properties");
		Files.deleteIfExists(path);
		System.setProperty("arena.client.perf.config", path.toString());

		ArenaClientPerfConfig loaded = ArenaClientPerfConfig.loadFrom(path);
		assertEquals(128, loaded.fighterRenderDistanceBlocks());
		assertFalse(loaded.fighterShadowsEnabled());
		assertTrue(loaded.adaptiveFighterRendering());

		Properties bad = new Properties();
		bad.setProperty("fighter_render_distance_blocks", "8");
		bad.setProperty("fighter_lod_mid_distance_blocks", "200");
		bad.setProperty("fighter_lod_far_distance_blocks", "10");
		ArenaClientPerfConfig clamped = ArenaClientPerfConfig.fromProperties(bad);
		assertEquals(128, clamped.fighterRenderDistanceBlocks());
		assertTrue(clamped.fighterLodFarDistanceBlocks() >= clamped.fighterLodMidDistanceBlocks());
		assertTrue(clamped.fighterRenderDistanceBlocks() >= clamped.fighterLodFarDistanceBlocks());

		Files.writeString(path, "{{{not-properties");
		ArenaClientPerfConfig corrupt = ArenaClientPerfConfig.loadFrom(path);
		assertEquals(128, corrupt.fighterRenderDistanceBlocks());

		Files.deleteIfExists(path);
		Files.deleteIfExists(Path.of(path.toString() + ".tmp"));
	}

	@Test
	void stableOverheadFlagsAlwaysWhenEntityRendered() {
		ArenaClientPerfConfig cfg = ArenaClientPerfConfig.defaults();
		ArenaFighterRenderDecision.AdaptiveState adaptive =
				ArenaFighterRenderDecision.adaptive(cfg, 0);

		// A standing / near
		ArenaFighterRenderDecision.FrameDecision near =
				ArenaFighterRenderDecision.decide(10 * 10, cfg, adaptive, false);
		assertEquals(ArenaFighterLodLevel.NEAR, near.lod());
		assertTrue(near.renderModel());
		assertTrue(near.renderSpear());
		assertTrue(near.renderOverhead());

		// B moving mid-range — flags must still be allowed (no LOD gate)
		ArenaFighterRenderDecision.FrameDecision mid =
				ArenaFighterRenderDecision.decide(40 * 40, cfg, adaptive, false);
		assertEquals(ArenaFighterLodLevel.MID, mid.lod());
		assertTrue(mid.renderModel());
		assertTrue(mid.renderSpear());
		assertTrue(mid.renderOverhead());

		// C far band — still overhead (stable visual > FPS)
		ArenaFighterRenderDecision.FrameDecision far =
				ArenaFighterRenderDecision.decide(80 * 80, cfg, adaptive, false);
		assertEquals(ArenaFighterLodLevel.FAR, far.lod());
		assertTrue(far.renderModel());
		assertTrue(far.renderSpear());
		assertTrue(far.renderOverhead());

		// D beyond render distance — culled entirely
		ArenaFighterRenderDecision.FrameDecision culled =
				ArenaFighterRenderDecision.decide(140 * 140, cfg, adaptive, true);
		assertEquals(ArenaFighterLodLevel.CULLED, culled.lod());
		assertFalse(culled.renderEntity());
	}

	@Test
	void adaptiveExtremeStillKeepsOverheadFlags() {
		ArenaClientPerfConfig cfg = ArenaClientPerfConfig.sanitize(
				128, 32, 64, 24, 20, true, true, 20, 48);
		ArenaFighterRenderDecision.AdaptiveState extreme =
				ArenaFighterRenderDecision.adaptive(cfg, 300);
		assertTrue(extreme.forceDisableNameplates());

		ArenaFighterRenderDecision.FrameDecision midBand =
				ArenaFighterRenderDecision.decide(50 * 50, cfg, extreme, false);
		assertTrue(midBand.renderModel());
		assertTrue(midBand.renderSpear());
		assertTrue(midBand.renderOverhead());
	}

	@Test
	void particleBudgetStillApplies() {
		ArenaClientPerfConfig cfg = ArenaClientPerfConfig.defaults();
		ArenaFighterRenderDecision.AdaptiveState adaptive =
				ArenaFighterRenderDecision.adaptive(cfg, 0);
		assertTrue(ArenaFighterRenderDecision.shouldSpawnArenaParticle(10 * 10, cfg, adaptive, 0, false));
		assertFalse(ArenaFighterRenderDecision.shouldSpawnArenaParticle(80 * 80, cfg, adaptive, 0, false));
	}

	@Test
	void rendererUsesStableOverheadPath() throws Exception {
		String renderer = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaFighterRenderer.java"),
				StandardCharsets.UTF_8);
		assertTrue(renderer.contains("ArenaFighterOverheadRenderer.render"));
		assertFalse(renderer.contains("decision.renderOverhead"));
		assertFalse(renderer.contains("shouldRender("));
		assertTrue(renderer.contains("ArenaFighterHeldItemLayer"));

		String overhead = Files.readString(
				Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaFighterOverheadRenderer.java"),
				StandardCharsets.UTF_8);
		assertTrue(overhead.contains("new Quaternionf(dispatcher.cameraOrientation())"));
		assertTrue(overhead.contains("shouldShowFlag"));
		assertFalse(overhead.contains("cameraOrientationScratch"));
	}
}
