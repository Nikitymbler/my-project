package com.nikita.arenaofnations;

/**
 * Russian pluralization for «победа» counts used by the Top-5 overlay.
 */
public final class ArenaRoundWinsGrammar {
	private ArenaRoundWinsGrammar() {
	}

	/** Returns {@code N победа|победы|побед} for non-negative {@code n}. */
	public static String formatWins(int n) {
		int abs = Math.abs(n);
		return abs + " " + winsWord(abs);
	}

	/** Word only: победа / победы / побед. */
	public static String winsWord(int n) {
		int abs = Math.abs(n) % 100;
		int last = abs % 10;
		if (abs > 10 && abs < 20) {
			return "побед";
		}
		if (last == 1) {
			return "победа";
		}
		if (last >= 2 && last <= 4) {
			return "победы";
		}
		return "побед";
	}
}
