package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArenaOverlayBackgroundModeTest {
	private static String read(String relative) throws Exception {
		return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
	}

	@Test
	void jsResolvesBackgroundPriorityAndDefaultsChroma() throws Exception {
		String js = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js");
		assertTrue(js.contains("function resolveBackgroundMode()"));
		assertTrue(js.contains("bg === \"chroma\""));
		assertTrue(js.contains("params.get(\"chroma\") === \"1\""));
		assertTrue(js.contains("bg === \"transparent\""));
		assertTrue(js.contains("return \"chroma\""));
		assertTrue(js.contains("CAPTURE MODE: CHROMA"));
		assertTrue(js.contains("CHROMA_COLOR = \"#FF00FF\""));
		assertTrue(js.contains("coreHp"));
		assertTrue(js.contains("coreMaxHp"));
		assertTrue(js.contains("core-hp"));
		assertTrue(js.contains("countries-grid") || js.contains("countries-1"));
		assertTrue(js.contains("densityClassForCount"));
		assertFalse(js.contains("updateFitScale"));
		assertFalse(js.contains("--fit-scale"));
		assertFalse(js.contains("--overlay-scale"));
		assertFalse(js.contains("slice(0, 10)"));
	}

	@Test
	void cssUsesMagentaChromaWithoutRootScale() throws Exception {
		String css = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.css");
		assertTrue(css.contains("background: #FF00FF !important"));
		assertTrue(css.contains("rgb(255, 0, 255)"));
		assertTrue(css.contains("1080px") || css.contains("1080"));
		assertTrue(css.contains("1920px") || css.contains("1920"));
		assertTrue(css.contains("#overlay-workspace") || css.contains("width: 1080px"));
		assertTrue(css.contains(".core-hp"));
		assertTrue(css.contains("--hp-high: #22c55e"));
		assertTrue(css.contains("#countries-grid"));
		assertTrue(css.contains("countries-13-20"));
		assertFalse(css.contains("transform: scale("));
		assertFalse(css.contains("--fit-scale"));
		assertFalse(css.contains("backdrop-filter: blur"));
		assertFalse(css.contains("#00FF00"));
		assertFalse(css.contains("color: #FF00FF"));
		assertTrue(css.contains("--chroma: #FF00FF") || css.contains("background: #FF00FF !important"));
	}

	@Test
	void htmlHasCaptureModeBadgeHiddenByDefault() throws Exception {
		String html = read("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html");
		assertTrue(html.contains("id=\"capture-mode-badge\""));
		assertTrue(html.contains("class=\"hidden preview-only\""));
		assertTrue(html.contains("id=\"countries-grid\""));
		assertTrue(html.contains("id=\"preview-diag\""));
	}

	@Test
	void openOverlayScriptsUseMagentaChromaAndNativeScaleFlags() throws Exception {
		String windowCmd = read("OPEN_OVERLAY_WINDOW.cmd");
		assertTrue(windowCmd.contains("cd /d \"%~dp0\""));
		assertTrue(windowCmd.contains("https://localhost:8766/overlay/tiktok?background=chroma"));
		assertTrue(windowCmd.contains("--app=\"%OVERLAY_URL%\""));
		assertTrue(windowCmd.contains("--window-size=1080,1920"));
		assertTrue(windowCmd.contains("--force-device-scale-factor=1"));
		assertTrue(windowCmd.contains("--high-dpi-support=1"));
		assertTrue(windowCmd.contains("#FF00FF"));
		assertFalse(windowCmd.toLowerCase().contains("d:\\minecraft"), "must not hardcode D: path");

		String previewCmd = read("OPEN_OVERLAY.cmd");
		assertTrue(previewCmd.contains("?background=chroma&preview=1"));
		assertTrue(previewCmd.contains("OPEN_OVERLAY_WINDOW.cmd"));
		assertTrue(previewCmd.contains("#FF00FF"));
	}
}
