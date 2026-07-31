package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Facade over {@link ArenaScoreSavedData}. Score points, roundWins, and fighter-round record
 * are independent statistics.
 */
public final class ArenaScoreManager {
	private static final int POINTS_HOLD = 1;
	private static final int POINTS_DUEL_WIN = 3;
	private static final int POINTS_MULTI_WIN = 5;

	private ArenaScoreManager() {
	}

	private static ArenaScoreSavedData getData(MinecraftServer server) {
		if (server == null) {
			ArenaOfNations.LOGGER.error("Cannot access arena scores: MinecraftServer is null");
			return null;
		}

		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			ArenaOfNations.LOGGER.error("Cannot access arena scores: overworld is unavailable");
			return null;
		}

		return overworld.getDataStorage().computeIfAbsent(ArenaScoreSavedData.FACTORY, ArenaScoreSavedData.DATA_NAME);
	}

	public static int getScore(MinecraftServer server, Country country) {
		ArenaScoreSavedData data = getData(server);
		return data == null ? 0 : data.getScore(country);
	}

	public static int getRoundWins(MinecraftServer server, Country country) {
		ArenaScoreSavedData data = getData(server);
		return data == null ? 0 : data.getRoundWins(country);
	}

	public static int getFighterRoundRecordCount(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		return data == null ? 0 : data.getFighterRoundRecordCount();
	}

	public static Country getFighterRoundRecordCountry(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		return data == null ? null : data.getFighterRoundRecordCountry();
	}

	public static String getFighterRoundRecordCountryId(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		return data == null ? "" : data.getFighterRoundRecordCountryId();
	}

	/**
	 * Updates persistent fighter-round record if {@code count} is strictly greater.
	 * @return true when the saved record changed
	 */
	public static boolean tryUpdateFighterRoundRecord(MinecraftServer server, Country country, int count) {
		ArenaScoreSavedData data = getData(server);
		if (data == null) {
			return false;
		}
		return data.tryUpdateFighterRoundRecord(country, count);
	}

	public static void resetAll(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		if (data == null) {
			return;
		}
		data.resetAll();
	}

	public static void resetScorePoints(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		if (data != null) {
			data.resetScorePoints();
		}
	}

	public static void resetRoundWins(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		if (data != null) {
			data.resetRoundWins();
		}
	}

	public static void resetFighterRoundRecord(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		if (data != null) {
			data.resetFighterRoundRecord();
		}
	}

	public static int addPoints(MinecraftServer server, Country country, int points) {
		ArenaScoreSavedData data = getData(server);
		if (data == null) {
			return 0;
		}
		return data.addPoints(country, points);
	}

	public static int addRoundWin(MinecraftServer server, Country country) {
		ArenaScoreSavedData data = getData(server);
		if (data == null || country == null) {
			return 0;
		}
		return data.addRoundWin(country);
	}

	public static List<Country> rankedCountries(MinecraftServer server) {
		List<Country> ranked = new ArrayList<>();
		for (Country country : Country.values()) {
			ranked.add(country);
		}
		ranked.sort(Comparator
				.comparingInt((Country country) -> getScore(server, country)).reversed()
				.thenComparingInt(Enum::ordinal));
		return ranked;
	}

	public static List<ArenaTopCountriesRanking.Entry> topByRoundWins(MinecraftServer server, int limit) {
		return ArenaTopCountriesRanking.rank(
				c -> getRoundWins(server, c),
				c -> getScore(server, c),
				limit);
	}

	public static void awardHold(MinecraftServer server, Country country) {
		int total = addPoints(server, country, POINTS_HOLD);
		int wins = addRoundWin(server, country);
		broadcast(server, Component.literal(
				country.getDisplayName() + " получает " + POINTS_HOLD + " очко за удержание арены."));
		broadcast(server, Component.literal(
				"Всего очков у " + country.getDisplayName() + ": " + total
						+ " · побед раундов: " + wins));
		ArenaRoundHudSync.pushNow(server);
	}

	public static void awardBattleWin(MinecraftServer server, Country country, int participantCount) {
		if (participantCount < 2) {
			return;
		}

		int points = participantCount >= 3 ? POINTS_MULTI_WIN : POINTS_DUEL_WIN;
		int total = addPoints(server, country, points);
		int wins = addRoundWin(server, country);

		if (points == POINTS_MULTI_WIN) {
			broadcast(server, Component.literal(
					country.getDisplayName() + " получает " + points + " очков за большую битву."));
		} else {
			broadcast(server, Component.literal(
					country.getDisplayName() + " получает " + points + " очка за победу."));
		}

		broadcast(server, Component.literal(
				"Всего очков у " + country.getDisplayName() + ": " + total
						+ " · побед раундов: " + wins));
		ArenaRoundHudSync.pushNow(server);
	}

	public static String buildScoresText(MinecraftServer server) {
		StringBuilder builder = new StringBuilder("Очки стран:");
		int place = 1;
		for (Country country : rankedCountries(server)) {
			builder.append('\n')
					.append(place)
					.append(". ")
					.append(country.getDisplayName())
					.append(" — ")
					.append(getScore(server, country))
					.append(" (побед: ")
					.append(getRoundWins(server, country))
					.append(')');
			place++;
		}
		Country recordCountry = getFighterRoundRecordCountry(server);
		int recordCount = getFighterRoundRecordCount(server);
		builder.append("\nРекорд бойцов за раунд: ");
		if (recordCountry == null || recordCount <= 0) {
			builder.append("—");
		} else {
			builder.append(recordCountry.getDisplayName()).append(" — ").append(recordCount);
		}
		return builder.toString();
	}

	private static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}
}
