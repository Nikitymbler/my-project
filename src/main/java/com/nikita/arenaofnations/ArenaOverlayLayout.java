package com.nikita.arenaofnations;

/**
 * Deterministic TikTok overlay card layout for a fixed 1080×1920 canvas.
 * No CSS scale — sizes are selected by displayed country count.
 */
public final class ArenaOverlayLayout {
	public static final int CANVAS_WIDTH = 1080;
	public static final int CANVAS_HEIGHT = 1920;

	public enum CardSizeMode {
		LARGE,
		MEDIUM,
		COMPACT,
		ULTRA_COMPACT
	}

	public record LayoutPlan(
			String densityClass,
			int columns,
			CardSizeMode cardSizeMode,
			int maxVisibleCountries) {
	}

	private ArenaOverlayLayout() {
	}

	public static LayoutPlan planFor(int countryCount) {
		int n = Math.max(0, countryCount);
		if (n <= 0) {
			return new LayoutPlan("countries-0", 1, CardSizeMode.LARGE, 0);
		}
		if (n == 1) {
			return new LayoutPlan("countries-1", 1, CardSizeMode.LARGE, 1);
		}
		if (n == 2) {
			return new LayoutPlan("countries-2", 1, CardSizeMode.LARGE, 2);
		}
		if (n <= 4) {
			return new LayoutPlan("countries-3-4", 2, CardSizeMode.MEDIUM, 4);
		}
		if (n <= 8) {
			return new LayoutPlan("countries-5-8", 2, CardSizeMode.COMPACT, 8);
		}
		if (n <= 12) {
			return new LayoutPlan("countries-9-12", 2, CardSizeMode.COMPACT, 12);
		}
		return new LayoutPlan("countries-13-20", 2, CardSizeMode.ULTRA_COMPACT, 20);
	}

	/**
	 * Rough max card height budget so 20 cards (10 rows × 2 cols) fit without scroll
	 * inside the grid area under the header.
	 */
	public static int estimatedGridHeightPx() {
		// header ~170, bottom safe ~72 → remaining for cards
		return CANVAS_HEIGHT - 170 - 72;
	}

	public static boolean fitsWithoutOverflow(int countryCount) {
		LayoutPlan plan = planFor(countryCount);
		if (countryCount <= 0) {
			return true;
		}
		int rows = (countryCount + plan.columns() - 1) / plan.columns();
		int rowGap = switch (plan.cardSizeMode()) {
			case LARGE -> 16;
			case MEDIUM -> 12;
			case COMPACT -> 8;
			case ULTRA_COMPACT -> 6;
		};
		int cardHeight = switch (plan.cardSizeMode()) {
			case LARGE -> 172;
			case MEDIUM -> 152;
			case COMPACT -> 136;
			case ULTRA_COMPACT -> 148;
		};
		int used = rows * cardHeight + Math.max(0, rows - 1) * rowGap;
		return used <= estimatedGridHeightPx();
	}
}
