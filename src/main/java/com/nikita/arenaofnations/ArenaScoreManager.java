package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Facade over {@link ArenaScoreSavedData} stored in the current world's overworld.
 * Does not keep a separate in-memory score table that could diverge from disk.
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

	public static void resetAll(MinecraftServer server) {
		ArenaScoreSavedData data = getData(server);
		if (data == null) {
			return;
		}
		data.resetAll();
	}

	public static int addPoints(MinecraftServer server, Country country, int points) {
		ArenaScoreSavedData data = getData(server);
		if (data == null) {
			return 0;
		}
		return data.addPoints(country, points);
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

	public static void awardHold(MinecraftServer server, Country country) {
		int total = addPoints(server, country, POINTS_HOLD);
		broadcast(server, Component.literal(
				country.getDisplayName() + " получает " + POINTS_HOLD + " очко за удержание арены."));
		broadcast(server, Component.literal(
				"Всего очков у " + country.getDisplayName() + ": " + total));
	}

	public static void awardBattleWin(MinecraftServer server, Country country, int participantCount) {
		if (participantCount < 2) {
			return;
		}

		int points = participantCount >= 3 ? POINTS_MULTI_WIN : POINTS_DUEL_WIN;
		int total = addPoints(server, country, points);

		if (points == POINTS_MULTI_WIN) {
			broadcast(server, Component.literal(
					country.getDisplayName() + " получает " + points + " очков за большую битву."));
		} else {
			broadcast(server, Component.literal(
					country.getDisplayName() + " получает " + points + " очка за победу."));
		}

		broadcast(server, Component.literal(
				"Всего очков у " + country.getDisplayName() + ": " + total));
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
					.append(getScore(server, country));
			place++;
		}
		return builder.toString();
	}

	private static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}
}
