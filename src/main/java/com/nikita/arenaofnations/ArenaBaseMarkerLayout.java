package com.nikita.arenaofnations;

/**
 * Shared layout math for world-space base flag / country-name markers.
 * Used by the client renderer and unit tests (no PoseStack / Minecraft client types).
 */
public final class ArenaBaseMarkerLayout {
	public static final float FLAG_HALF_W = 2.5F;
	public static final float FLAG_HALF_H = 1.5F;
	/** World-space gap above the flag top edge (blocks). */
	public static final float NAME_WORLD_GAP = 0.85F;
	/** Vanilla-like name-tag text scale. */
	public static final float NAME_TEXT_SCALE = 0.045F;

	private ArenaBaseMarkerLayout() {
	}

	/** World Y of the flag top edge for a vertical camera-facing billboard. */
	public static double flagTopWorldY(double flagCenterY) {
		return flagCenterY + FLAG_HALF_H;
	}

	/** World Y of the country name billboard (above the flag). */
	public static double labelWorldY(double flagCenterY) {
		return flagTopWorldY(flagCenterY) + NAME_WORLD_GAP;
	}

	public static boolean labelIsAboveFlag(double flagCenterY, double labelY) {
		return labelY > flagTopWorldY(flagCenterY) + 0.01D;
	}

	public static boolean labelDiffersFromFlagCenter(double flagCenterY, double labelY) {
		return Math.abs(labelY - flagCenterY) > FLAG_HALF_H + 0.2D;
	}

	/** Centered draw X for a string of the given pixel width. */
	public static float centeredTextX(int fontWidthPx) {
		return -fontWidthPx * 0.5F;
	}
}
