package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArenaInGameHudDisabledTest {
	@Test
	void roundHudRendererDoesNotRegisterHudCallback() throws Exception {
		Path renderer = Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaRoundHudRenderer.java");
		String source = Files.readString(renderer, StandardCharsets.UTF_8);
		assertFalse(
				source.contains("HudRenderCallback.EVENT.register("),
				"must not register HUD callback");
		assertFalse(source.contains("import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback"));
		assertTrue(source.contains("IN_GAME_HUD_RENDER_PATHS = 0"));
		assertTrue(source.contains("IN_GAME_HUD_ENABLED = false"));
		assertTrue(source.contains("isRendererRegistered()"));
		assertTrue(source.contains("return false"));
	}

	@Test
	void clientStillKeepsSnapshotReceiverAndBaseMarkers() throws Exception {
		Path client = Path.of("src/client/java/com/nikita/arenaofnations/client/ArenaOfNationsClient.java");
		String source = Files.readString(client, StandardCharsets.UTF_8);
		assertTrue(source.contains("ArenaRoundHudClient.register()"));
		assertTrue(source.contains("ArenaBaseMarkerRenderer.register()"));
		assertTrue(source.contains("ArenaRoundHudRenderer.register()"));
	}

	@Test
	void overlayStatusReportsHudDisabled() throws Exception {
		Path commands = Path.of("src/main/java/com/nikita/arenaofnations/ArenaOverlayCommands.java");
		String source = Files.readString(commands, StandardCharsets.UTF_8);
		assertTrue(source.contains("primaryOverlayMode=TIKTOK_WINDOW_CHROMA"));
		assertTrue(source.contains("inGameHudEnabled=false"));
		assertTrue(source.contains("inGameHudRendererRegistered=false"));
		assertTrue(source.contains("inGameHudRenderPaths=0"));
		assertTrue(source.contains("chromaKeyColor=#FF00FF"));
		assertTrue(source.contains("nativeCanvasWidth=1080"));
		assertTrue(source.contains("cssRootScale=1"));
		assertTrue(source.contains("coreHpFieldsAvailable="));
		assertTrue(source.contains("getChromaOverlayUrl()"));
	}

	@Test
	void defaultHudModeIsOff() throws Exception {
		Path config = Path.of("src/main/java/com/nikita/arenaofnations/ArenaConfig.java");
		String source = Files.readString(config, StandardCharsets.UTF_8);
		assertTrue(source.contains("defaultHudMode = ArenaHudDisplayMode.OFF"));
		assertTrue(source.contains("\"default_hud_mode\", \"off\""));
		assertEquals(ArenaHudDisplayMode.OFF, ArenaHudDisplayMode.parse("off", ArenaHudDisplayMode.EXTERNAL));
	}
}
