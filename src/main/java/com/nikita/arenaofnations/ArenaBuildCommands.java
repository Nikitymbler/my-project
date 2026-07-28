package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class ArenaBuildCommands {
	private ArenaBuildCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> build = Commands.literal("arena_build");
		build.executes(context -> buildHelp(context.getSource()));
		build.then(Commands.literal("confirm")
				.requires(source -> source.hasPermission(2))
				.executes(context -> buildConfirm(context.getSource())));
		dispatcher.register(build);

		dispatcher.register(Commands.literal("arena_build_status")
				.executes(context -> buildStatus(context.getSource())));

		LiteralArgumentBuilder<CommandSourceStack> cancel = Commands.literal("arena_cancel_build");
		cancel.executes(context -> cancelHelp(context.getSource()));
		cancel.then(Commands.literal("confirm")
				.requires(source -> source.hasPermission(2))
				.executes(context -> cancelConfirm(context.getSource())));
		dispatcher.register(cancel);

		LiteralArgumentBuilder<CommandSourceStack> clear = Commands.literal("arena_setup_clear");
		clear.executes(context -> clearHelp(context.getSource()));
		clear.then(Commands.literal("confirm")
				.requires(source -> source.hasPermission(2))
				.executes(context -> clearConfirm(context.getSource())));
		dispatcher.register(clear);
	}

	private static int buildHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Команда изменит блоки в радиусе " + ArenaCountryBaseLayout.CLEAR_RADIUS
						+ " блоков и построит арену v3.\n"
						+ "Для подтверждения используйте /arena_build confirm"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int buildConfirm(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BlockPos standing = player.blockPosition().below();
		if (!player.serverLevel().getBlockState(standing).blocksMotion()) {
			standing = player.blockPosition();
		}

		ArenaSetupSavedData existing = ArenaSetupSavedData.get(source.getServer());
		if (existing != null && (existing.isConfigured() || existing.isBuilt())) {
			source.sendSuccess(() -> Component.literal("Существующая настройка арены будет заменена новой."), false);
		}

		String error = ArenaBuildManager.startBuild(source.getServer(), player, standing);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int buildStatus(CommandSourceStack source) {
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(source.getServer());
		StringBuilder builder = new StringBuilder("Статус арены:\n");
		if (setup == null) {
			builder.append("Хранилище недоступно.");
			source.sendSuccess(() -> Component.literal(builder.toString()), false);
			return Command.SINGLE_SUCCESS;
		}

		builder.append("configured=").append(setup.isConfigured()).append('\n');
		builder.append("built=").append(setup.isBuilt()).append('\n');
		builder.append("build_version=").append(setup.getBuildVersion()).append('\n');
		builder.append("dimension=").append(setup.getDimension().isEmpty() ? "-" : setup.getDimension()).append('\n');
		builder.append("center=")
				.append(setup.getCenterX()).append(' ')
				.append(setup.getCenterY()).append(' ')
				.append(setup.getCenterZ()).append('\n');
		builder.append("center_pattern_radius=").append(ArenaPositions.CENTER_PATTERN_RADIUS).append('\n');
		builder.append("combat_walkable_radius=").append(ArenaPositions.COMBAT_WALKABLE_RADIUS).append('\n');
		builder.append("outer_radius=").append(ArenaPositions.OUTER_RADIUS).append('\n');

		ArenaBuildManager.ActiveBuild job = ArenaBuildManager.getActive();
		boolean building = ArenaBuildManager.isBuilding();
		builder.append("building=").append(building).append('\n');
		if (job != null && building) {
			builder.append("stage=").append(job.stageLabel()).append('\n');
			builder.append("progress=").append(job.percent()).append("%\n");
			builder.append("ops_done=").append(job.opsDone).append('\n');
			builder.append("ops_total_est=").append(job.estimatedTotal()).append('\n');
		} else if (setup.isConfigured() && !setup.isBuilt()) {
			builder.append("stage=не завершена\n");
		}

		if (setup.isConfigured()) {
			BlockPos center = setup.getCenter();
			builder.append("Точки появления:\n");
			for (Country country : Country.values()) {
				BlockPos spawn = ArenaPositions.getCountryBase(center, country);
				builder.append("- ")
						.append(country.getDisplayName())
						.append(": ")
						.append(spawn.getX()).append(' ')
						.append(spawn.getY()).append(' ')
						.append(spawn.getZ())
						.append('\n');
			}
			builder.append("Ядра:\n");
			for (Country country : Country.values()) {
				BlockPos core = ArenaPositions.getCorePosition(center, country);
				builder.append("- ")
						.append(country.getDisplayName())
						.append(": ")
						.append(core.getX()).append(' ')
						.append(core.getY()).append(' ')
						.append(core.getZ())
						.append('\n');
			}
		}

		source.sendSuccess(() -> Component.literal(builder.toString().trim()), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int cancelHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Отмена оставит уже установленные блоки.\n"
						+ "Используйте /arena_cancel_build confirm"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int cancelConfirm(CommandSourceStack source) {
		String error = ArenaBuildManager.cancelBuild(source.getServer());
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int clearHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Команда удалит блоки арены в радиусе " + ArenaCountryBaseLayout.CLEAR_RADIUS
						+ " и сохранённую настройку.\n"
						+ "Используйте /arena_setup_clear confirm"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int clearConfirm(CommandSourceStack source) {
		if (ArenaBuildManager.isBuilding()) {
			source.sendFailure(Component.literal(
					"Сначала остановите строительство: /arena_cancel_build confirm"));
			return 0;
		}
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(source.getServer());
		if (setup == null) {
			source.sendFailure(Component.literal("Хранилище настройки недоступно."));
			return 0;
		}
		BlockPos center = setup.isConfigured() ? setup.getCenter() : null;
		ServerLevel arenaLevel = ArenaBuildManager.resolveArenaLevel(source.getServer());
		int cleared = 0;
		if (setup.isConfigured() && arenaLevel != null && center != null) {
			cleared = ArenaRegionClear.clearArena(arenaLevel, center);
		}
		setup.clearSetup();
		ArenaCoreCombatManager.get().clearAll(source.getServer());
		ArenaCoreRescueManager.get().clearAll();
		ArenaHudManager.get().clearAll(source.getServer());
		int finalCleared = cleared;
		source.sendSuccess(() -> Component.literal(
				"Арена очищена (блоков/сущностей: " + finalCleared + "). Сохранённая настройка сброшена."), false);
		return Command.SINGLE_SUCCESS;
	}
}
