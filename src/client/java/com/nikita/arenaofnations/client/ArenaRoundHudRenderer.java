package com.nikita.arenaofnations.client;

/**
 * Former in-game screen HUD (Fabric HUD render callback).
 * Permanently disabled — match info is shown only via the browser overlay window.
 * Snapshot networking ({@link ArenaRoundHudClient}) remains for base markers / diagnostics.
 */
public final class ArenaRoundHudRenderer {
	public static final int IN_GAME_HUD_RENDER_PATHS = 0;
	public static final boolean IN_GAME_HUD_ENABLED = false;

	private static boolean registerCalled;

	private ArenaRoundHudRenderer() {
	}

	/**
	 * No-op: does not register any client HUD render callback.
	 * Kept so call sites compile and diagnostics stay explicit.
	 */
	public static void register() {
		registerCalled = true;
	}

	public static boolean isRendererRegistered() {
		return false;
	}

	public static boolean wasRegisterInvoked() {
		return registerCalled;
	}

	public static int renderPathCount() {
		return IN_GAME_HUD_RENDER_PATHS;
	}

	public static String debugReport(int screenWidth, int screenHeight) {
		return "In-game Arena HUD disabled (browser overlay only):\n"
				+ "inGameHudEnabled=false\n"
				+ "inGameHudRendererRegistered=false\n"
				+ "inGameHudRenderPaths=0\n"
				+ "guiWidth=" + screenWidth + " guiHeight=" + screenHeight + "\n"
				+ "registerInvoked=" + registerCalled;
	}
}
