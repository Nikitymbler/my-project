package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Top-N countries by {@code roundWins}, then score points, then stable country id.
 * Countries with zero wins are omitted (empty → «ПОКА НЕТ ПОБЕД» on the client).
 */
public final class ArenaTopCountriesRanking {
	public static final int DEFAULT_LIMIT = 5;
	public static final String SOURCE = "ROUND_WINS";

	private ArenaTopCountriesRanking() {
	}

	public record Entry(int rank, Country country, int roundWins, int scorePoints) {
	}

	public static List<Entry> rank(
			ToIntFunction<Country> roundWins,
			ToIntFunction<Country> scorePoints,
			int limit) {
		int capped = Math.max(0, limit);
		List<Country> withWins = new ArrayList<>();
		for (Country country : Country.values()) {
			if (roundWins.applyAsInt(country) > 0) {
				withWins.add(country);
			}
		}
		withWins.sort(Comparator
				.comparingInt((Country c) -> roundWins.applyAsInt(c)).reversed()
				.thenComparing(Comparator.comparingInt((Country c) -> scorePoints.applyAsInt(c)).reversed())
				.thenComparingInt(Enum::ordinal));

		List<Entry> result = new ArrayList<>();
		int rank = 1;
		for (Country country : withWins) {
			if (result.size() >= capped) {
				break;
			}
			result.add(new Entry(
					rank,
					country,
					roundWins.applyAsInt(country),
					scorePoints.applyAsInt(country)));
			rank++;
		}
		return result;
	}

	public static List<Entry> rank(
			Map<Country, Integer> wins,
			Map<Country, Integer> scores,
			int limit) {
		Map<Country, Integer> winMap = wins == null ? Map.of() : wins;
		Map<Country, Integer> scoreMap = scores == null ? Map.of() : scores;
		return rank(
				c -> winMap.getOrDefault(c, 0),
				c -> scoreMap.getOrDefault(c, 0),
				limit);
	}

	public static Map<Country, Integer> emptyWinsMap() {
		EnumMap<Country, Integer> map = new EnumMap<>(Country.class);
		for (Country country : Country.values()) {
			map.put(country, 0);
		}
		return map;
	}
}
