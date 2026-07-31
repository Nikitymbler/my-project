package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

final class ArenaMatchCommands {
	private ArenaMatchCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena_gift")
				.then(Commands.argument("country", StringArgumentType.word())
						.suggests(ArenaMatchCommands::suggestCountries)
						.then(Commands.argument("coins", IntegerArgumentType.integer(1))
								.executes(ArenaMatchCommands::giftCommand))));

		dispatcher.register(Commands.literal("arena_status")
				.executes(ArenaMatchCommands::statusCommand));

		dispatcher.register(Commands.literal("arena_round_reset")
				.executes(ArenaMatchCommands::resetCommand));

		dispatcher.register(Commands.literal("arena_config_reload")
				.executes(ArenaMatchCommands::reloadCommand));

		dispatcher.register(Commands.literal("arena_config_status")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaMatchCommands::configStatusCommand));

		dispatcher.register(Commands.literal("arena_reserve_batch")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaMatchCommands::reserveBatchStatusCommand)
				.then(Commands.argument("batch", IntegerArgumentType.integer())
						.executes(ArenaMatchCommands::reserveBatchSetCommand)));

		dispatcher.register(Commands.literal("arena_damage_stats")
				.executes(ArenaMatchCommands::damageStatsCommand));

		dispatcher.register(Commands.literal("arena_scores")
				.executes(ArenaMatchCommands::scoresCommand));

		dispatcher.register(Commands.literal("arena_scores_reset")
				.executes(ArenaMatchCommands::scoresResetCommand));

		dispatcher.register(Commands.literal("arena_ai_status")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaMatchCommands::aiStatusCommand));

		dispatcher.register(Commands.literal("arena_class_status")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaMatchCommands::classStatusCommand));
	}

	private static int giftCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();

		Country country = Country.byId(StringArgumentType.getString(context, "country"));
		if (country == null) {
			source.sendFailure(Component.literal(
					"Неизвестная страна. Используй код: " + String.join(", ", Country.allIds())));
			return 0;
		}

		int coins = IntegerArgumentType.getInteger(context, "coins");
		if (coins < 1) {
			source.sendFailure(Component.literal(
					"Подарок слишком маленький. Минимум: 1 монета."));
			return 0;
		}

		ArenaMatchManager.get().handleGift(
				source.getServer(),
				player.serverLevel(),
				player.position(),
				country,
				coins);

		source.sendSuccess(
				() -> Component.literal("Подарок: " + country.getDisplayName() + " +" + coins
						+ " → " + coins + " бойцов"),
				false);
		return 1;
	}

	private static int statusCommand(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String status = ArenaMatchManager.get().buildStatusText(source.getLevel());
		source.sendSuccess(() -> Component.literal(status), false);
		return 1;
	}

	private static int resetCommand(CommandContext<CommandSourceStack> context) {
		ArenaMatchManager.get().reset(context.getSource().getServer());
		context.getSource().sendSuccess(() -> Component.literal("Раунд сброшен."), false);
		return 1;
	}

	private static int reloadCommand(CommandContext<CommandSourceStack> context) {
		ArenaConfig.get().reload();
		context.getSource().sendSuccess(() -> Component.literal("Конфигурация арены перезагружена."), false);
		return 1;
	}

	private static int reserveBatchStatusCommand(CommandContext<CommandSourceStack> context) {
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		context.getSource().sendSuccess(
				() -> Component.literal(
						"Размер выпуска резерва: "
								+ settings.getReserveReleaseBatch()
								+ " бойцов за волну\nДопустимый диапазон: "
								+ ArenaReserveRuntimeSettings.MIN_BATCH
								+ "-"
								+ ArenaReserveRuntimeSettings.MAX_BATCH),
				false);
		return 1;
	}

	private static int reserveBatchSetCommand(CommandContext<CommandSourceStack> context) {
		int batch = IntegerArgumentType.getInteger(context, "batch");
		ArenaReserveRuntimeSettings.ApplyResult result = ArenaReserveRuntimeSettings.get().apply(
				batch,
				ArenaReserveRuntimeSettings.ChangedBy.COMMAND);
		if (!result.success()) {
			context.getSource().sendFailure(Component.literal(result.message()));
			return 0;
		}
		context.getSource().sendSuccess(
				() -> Component.literal(
						"Размер следующего выпуска резерва изменён на "
								+ result.reserveReleaseBatch()
								+ " бойцов на страну."),
				false);
		return 1;
	}

	private static int configStatusCommand(CommandContext<CommandSourceStack> context) {
		ArenaConfig config = ArenaConfig.get();
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		StringBuilder report = new StringBuilder();
		report.append("Arena config status:\n");
		report.append("reserve_wave_size=").append(config.getReserveWaveSize()).append('\n');
		report.append("reserve_wave_interval_ticks=").append(config.getReserveWaveIntervalTicks()).append('\n');
		report.append("activeFightersLimit=").append(settings.getActiveFightersLimit()).append('\n');
		report.append(settings.buildDiagnosticLines());
		context.getSource().sendSuccess(() -> Component.literal(report.toString()), false);
		return 1;
	}

	private static int damageStatsCommand(CommandContext<CommandSourceStack> context) {
		String stats = ArenaMatchManager.get().buildDamageStatsText();
		context.getSource().sendSuccess(() -> Component.literal(stats), false);
		return 1;
	}

	private static int scoresCommand(CommandContext<CommandSourceStack> context) {
		String scores = ArenaScoreManager.buildScoresText(context.getSource().getServer());
		context.getSource().sendSuccess(() -> Component.literal(scores), false);
		return 1;
	}

	private static int scoresResetCommand(CommandContext<CommandSourceStack> context) {
		ArenaScoreManager.resetAll(context.getSource().getServer());
		context.getSource().sendSuccess(() -> Component.literal("Очки всех стран обнулены."), false);
		return 1;
	}

	private static int aiStatusCommand(CommandContext<CommandSourceStack> context) {
		ServerLevel level = context.getSource().getLevel();
		ArenaMatchManager match = ArenaMatchManager.get();
		StringBuilder report = new StringBuilder(FighterTargeting.buildAiStatus(level));
		report.append('\n').append("battleTicks=").append(match.getBattleTicksElapsed());
		report.append('\n').append("lastWave total=").append(match.getLastWaveReleasedTotal())
				.append(" RU=").append(match.getLastWaveReleased(Country.RU))
				.append(" UA=").append(match.getLastWaveReleased(Country.UA));
		report.append('\n').append("reserve RU=").append(match.getReserveSize(Country.RU))
				.append(" UA=").append(match.getReserveSize(Country.UA));
		report.append('\n').append("living RU=").append(match.countLivingFighters(level, Country.RU))
				.append(" UA=").append(match.countLivingFighters(level, Country.UA));
		AiSpawnMetrics metrics = collectSpawnMetrics(level, match);
		report.append('\n').append("fighters inside spawn zone=").append(metrics.insideSpawnZones);
		report.append('\n').append("fighters below expected floor=").append(metrics.belowFloor);
		report.append('\n').append("fighters with no path=").append(metrics.noPath);
		report.append('\n').append("fighters moving to rally=").append(metrics.movingToRally);
		report.append('\n').append("spawn distance avg=")
				.append(String.format(java.util.Locale.ROOT, "%.2f", metrics.averageDistance))
				.append(" min=")
				.append(String.format(java.util.Locale.ROOT, "%.2f", metrics.minDistance));
		if (ArenaMassDuelReserveTest.get().isRunning()) {
			report.append('\n').append(ArenaMassDuelReserveTest.get().statusReport(context.getSource().getServer()));
		}
		context.getSource().sendSuccess(() -> Component.literal(report.toString()), false);
		return 1;
	}

	private static AiSpawnMetrics collectSpawnMetrics(ServerLevel level, ArenaMatchManager match) {
		AiSpawnMetrics metrics = new AiSpawnMetrics();
		if (match.getMatchCenter().equals(net.minecraft.world.phys.Vec3.ZERO)) {
			return metrics;
		}
		BlockPos center = BlockPos.containing(match.getMatchCenter());
		double sum = 0.0D;
		double min = Double.MAX_VALUE;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)) {
				continue;
			}
			Country country = fighter.getArenaCountry();
			int slot = country == null ? -1 : match.getBaseSlot(country);
			if (slot < 0) {
				continue;
			}

			BlockPos spawnCenter = ArenaCountryBaseLayout.spawnZoneCenter(center, slot);
			double distFromSpawn = Math.sqrt(spawnCenter.distSqr(fighter.blockPosition()));
			sum += distFromSpawn;
			min = Math.min(min, distFromSpawn);
			metrics.counted++;
			if (distFromSpawn <= 7.0D) {
				metrics.insideSpawnZones++;
			}

			int expectedFloor = spawnCenter.getY() - 1;
			if (fighter.getY() < expectedFloor - 0.2D) {
				metrics.belowFloor++;
			}

			if (ArenaCoreCombatManager.get().isRallyOnly(fighter.getUUID())) {
				metrics.movingToRally++;
			}

			boolean hasPath = fighter.getNavigation().isInProgress() && !fighter.getNavigation().isDone();
			if (!hasPath && fighter.getTarget() == null) {
				metrics.noPath++;
			}
		}
		metrics.averageDistance = metrics.counted > 0 ? (sum / metrics.counted) : 0.0D;
		metrics.minDistance = metrics.counted > 0 ? min : 0.0D;
		return metrics;
	}

	private static final class AiSpawnMetrics {
		int counted;
		int insideSpawnZones;
		int belowFloor;
		int noPath;
		int movingToRally;
		double averageDistance;
		double minDistance;
	}

	private static int classStatusCommand(CommandContext<CommandSourceStack> context) {
		String report = "Классовые способности отключены в упрощённом режиме.\n"
				+ "Единственный класс: Боец (SCOUT-статы).";
		context.getSource().sendSuccess(() -> Component.literal(report), false);
		return 1;
	}

	private static CompletableFuture<Suggestions> suggestCountries(
			CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder) {
		List<String> ids = new ArrayList<>();
		for (Country country : Country.values()) {
			ids.add(country.getId());
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	}
}
