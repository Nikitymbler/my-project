package com.nikita.arenaofnations.client;

/**
 * Adaptive layout metrics for round HUD v3.
 */
public final class ArenaHudLayout {
	public enum Mode {
		LARGE,
		COMPACT,
		ULTRA
	}

	public record Metrics(
			Mode mode,
			int rowHeight,
			int rowGap,
			int columnGap,
			int columnWidth,
			int contentWidth,
			int flagWidth,
			int flagHeight,
			int codeWidth,
			int fightersWidth,
			int barWidth,
			int percentWidth,
			int statusWidth,
			int columns,
			int rowsPerColumn,
			int startY,
			int bottomLimit,
			int clippedRows,
			boolean overlapDetected) {
	}

	public record Bounds(int left, int top, int right, int bottom) {
	}

	private static final int HOTBAR_SAFE = 40;
	private static final int SCREEN_MARGIN = 4;

	private static Metrics lastMetrics = defaultMetrics();
	private static Bounds lastBounds = new Bounds(0, 0, 0, 0);

	private ArenaHudLayout() {
	}

	public static Metrics compute(int screenWidth, int screenHeight, int countryCount) {
		Mode mode = modeFor(countryCount);
		int rowHeight;
		int flagW;
		int flagH;
		int colWidth;
		int barW;
		int startY;
		int columnGap;
		int columns;

		switch (mode) {
			case LARGE -> {
				rowHeight = 38;
				flagW = 28;
				flagH = 17;
				colWidth = Math.min(340, Math.max(280, screenWidth / 2 - 24));
				barW = Math.min(120, colWidth - 160);
				startY = 32;
				columnGap = 10;
				columns = Math.min(2, Math.max(1, countryCount));
			}
			case COMPACT -> {
				rowHeight = 26;
				flagW = 22;
				flagH = 14;
				colWidth = Math.min(300, Math.max(260, screenWidth / 2 - 20));
				barW = 72;
				startY = 30;
				columnGap = 8;
				columns = 2;
			}
			default -> {
				rowHeight = 20;
				flagW = 22;
				flagH = 13;
				colWidth = Math.min(235, Math.max(205, (screenWidth - 48) / 4));
				barW = 58;
				startY = 30;
				columnGap = 8;
				columns = 4;
			}
		}

		int rowsPerColumn = (countryCount + columns - 1) / columns;
		int bottomLimit = screenHeight - HOTBAR_SAFE;
		int rowGap = 2;
		int clippedRows = 0;
		boolean overlap = false;

		int totalHeight = startY + rowsPerColumn * (rowHeight + rowGap);
		if (totalHeight > bottomLimit) {
			overlap = true;
			if (mode == Mode.ULTRA) {
				rowHeight = 18;
				flagH = 12;
				barW = 52;
				rowGap = 1;
				columnGap = 6;
				totalHeight = startY + rowsPerColumn * (rowHeight + rowGap);
			}
			if (totalHeight > bottomLimit && mode == Mode.COMPACT) {
				rowHeight = 24;
				barW = 64;
				totalHeight = startY + rowsPerColumn * (rowHeight + rowGap);
			}
		}

		if (totalHeight > bottomLimit) {
			clippedRows = (int) Math.ceil((totalHeight - bottomLimit) / (double) (rowHeight + rowGap));
			overlap = true;
		}

		int contentWidth = flagW + 3 + 28 + 44 + barW + 30 + 28 + 6;

		Metrics metrics = new Metrics(
				mode,
				rowHeight,
				rowGap,
				columnGap,
				colWidth,
				Math.min(contentWidth, colWidth),
				flagW,
				flagH,
				28,
				44,
				barW,
				30,
				28,
				columns,
				rowsPerColumn,
				startY,
				bottomLimit,
				clippedRows,
				overlap);

		int panelWidth = columns * colWidth + (columns - 1) * columnGap;
		int left = Math.max(SCREEN_MARGIN, (screenWidth - panelWidth) / 2);
		int right = Math.min(screenWidth - SCREEN_MARGIN, left + panelWidth);
		int bottom = Math.min(bottomLimit, startY + rowsPerColumn * (rowHeight + rowGap));
		lastMetrics = metrics;
		lastBounds = new Bounds(left, startY, right, bottom);
		return metrics;
	}

	public static Metrics lastMetrics() {
		return lastMetrics;
	}

	public static Bounds lastBounds() {
		return lastBounds;
	}

	private static Mode modeFor(int count) {
		if (count <= 4) {
			return Mode.LARGE;
		}
		if (count <= 10) {
			return Mode.COMPACT;
		}
		return Mode.ULTRA;
	}

	private static Metrics defaultMetrics() {
		return new Metrics(Mode.ULTRA, 20, 2, 8, 220, 200, 22, 13, 28, 44, 58, 30, 28, 4, 5, 30, 680, 0, false);
	}
}
