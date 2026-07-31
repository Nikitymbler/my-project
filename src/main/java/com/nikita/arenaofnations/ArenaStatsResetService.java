package com.nikita.arenaofnations;

import net.minecraft.server.MinecraftServer;

/**
 * Phase-gated stats reset helpers used by overlay HTTP API and commands.
 */
public final class ArenaStatsResetService {
	public enum ResetType {
		ROUND_WINS,
		SCORE_POINTS,
		FIGHTER_RECORD,
		ALL
	}

	private static volatile String lastResetType = "-";
	private static volatile boolean lastResetSuccess = false;
	private static volatile String lastResetError = "";

	private ArenaStatsResetService() {
	}

	public static String lastResetType() {
		return lastResetType;
	}

	public static boolean lastResetSuccess() {
		return lastResetSuccess;
	}

	public static String lastResetError() {
		return lastResetError == null ? "" : lastResetError;
	}

	public static boolean isResetAllowed() {
		ArenaMatchState state = ArenaMatchManager.get().getState();
		return state == ArenaMatchState.IDLE || state == ArenaMatchState.BREAK;
	}

	public static Result reset(MinecraftServer server, ResetType type) {
		if (server == null) {
			return fail(type, "no_server");
		}
		if (!isResetAllowed()) {
			return fail(type, "unsafe_phase");
		}
		try {
			switch (type) {
				case ROUND_WINS -> ArenaScoreManager.resetRoundWins(server);
				case SCORE_POINTS -> ArenaScoreManager.resetScorePoints(server);
				case FIGHTER_RECORD -> ArenaScoreManager.resetFighterRoundRecord(server);
				case ALL -> ArenaScoreManager.resetAll(server);
			}
			lastResetType = type.name();
			lastResetSuccess = true;
			lastResetError = "";
			ArenaRoundHudSync.pushNow(server);
			return new Result(true, type, successMessage(type), 200);
		} catch (Exception e) {
			return fail(type, e.getClass().getSimpleName());
		}
	}

	private static Result fail(ResetType type, String reason) {
		lastResetType = type == null ? "-" : type.name();
		lastResetSuccess = false;
		lastResetError = reason == null ? "error" : reason;
		int code = "unsafe_phase".equals(reason) ? 409 : 400;
		String message = switch (reason == null ? "" : reason) {
			case "unsafe_phase" -> "Сброс доступен только в IDLE или BREAK";
			case "no_server" -> "Сервер недоступен";
			default -> "Ошибка сброса: " + reason;
		};
		return new Result(false, type, message, code);
	}

	private static String successMessage(ResetType type) {
		return switch (type) {
			case ROUND_WINS -> "ТОП-5 побед сброшен";
			case SCORE_POINTS -> "Очки стран сброшены";
			case FIGHTER_RECORD -> "Рекорд бойцов сброшен";
			case ALL -> "Вся статистика сброшена";
		};
	}

	public record Result(boolean success, ResetType type, String message, int httpStatus) {
	}
}
