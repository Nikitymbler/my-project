package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

final class ArenaCoreCommands {
	private ArenaCoreCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> coresBuild = Commands.literal("arena_cores_build");
		coresBuild.executes(context -> coresBuildHelp(context.getSource()));
		coresBuild.then(Commands.literal("confirm")
				.requires(source -> source.hasPermission(2))
				.executes(context -> coresBuildConfirm(context.getSource())));
		dispatcher.register(coresBuild);

		dispatcher.register(Commands.literal("arena_cores_status")
				.executes(context -> coresStatus(context.getSource())));

		dispatcher.register(Commands.literal("arena_core_damage")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("country", StringArgumentType.word())
						.suggests(ArenaCoreCommands::suggestCountries)
						.then(Commands.argument("amount", FloatArgumentType.floatArg(0.1F))
								.executes(ArenaCoreCommands::coreDamage))));

		dispatcher.register(Commands.literal("arena_core_heal")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("country", StringArgumentType.word())
						.suggests(ArenaCoreCommands::suggestCountries)
						.then(Commands.argument("amount", FloatArgumentType.floatArg(0.1F))
								.executes(ArenaCoreCommands::coreHeal))));

		dispatcher.register(Commands.literal("arena_cores_reset")
				.requires(source -> source.hasPermission(2))
				.executes(context -> coresReset(context.getSource())));

		dispatcher.register(Commands.literal("arena_core_combat_status")
				.requires(source -> source.hasPermission(2))
				.executes(context -> coreCombatStatus(context.getSource())));

		dispatcher.register(Commands.literal("arena_core_damage_stats")
				.requires(source -> source.hasPermission(2))
				.executes(context -> coreDamageStats(context.getSource())));

		dispatcher.register(Commands.literal("arena_rescue_status")
				.requires(source -> source.hasPermission(2))
				.executes(context -> rescueStatus(context.getSource())));
	}

	private static int coreCombatStatus(CommandSourceStack source) {
		String text = ArenaCoreCombatManager.get().buildCombatStatusText(source.getServer(), source.getLevel());
		source.sendSuccess(() -> Component.literal(text), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coreDamageStats(CommandSourceStack source) {
		String text = ArenaCoreManager.get().buildCoreDamageStatsText(source.getServer(), source.getLevel());
		source.sendSuccess(() -> Component.literal(text), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int rescueStatus(CommandSourceStack source) {
		String text = ArenaCoreRescueManager.get().buildRescueStatusText(source.getServer());
		source.sendSuccess(() -> Component.literal(text), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coresBuildHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Команда построит или перезапишет четыре ядра существующей арены.\n"
						+ "Используйте /arena_cores_build confirm"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coresBuildConfirm(CommandSourceStack source) {
		if (ArenaBuildManager.isBuilding()) {
			source.sendFailure(Component.literal("Сначала дождитесь или отмените основное строительство арены."));
			return 0;
		}

		ArenaSetupSavedData setup = ArenaSetupSavedData.get(source.getServer());
		if (setup == null || !setup.isConfigured() || !setup.isBuilt()) {
			source.sendFailure(Component.literal("Арена должна быть настроена и построена (configured+built)."));
			return 0;
		}

		ServerLevel level = ArenaBuildManager.resolveArenaLevel(source.getServer());
		if (level == null) {
			source.sendFailure(Component.literal("Измерение арены недоступно."));
			return 0;
		}

		BlockPos center = setup.getCenter();
		for (Country country : Country.values()) {
			if (!ArenaPositions.isCoreInsideArena(center, country)) {
				source.sendFailure(Component.literal("Позиция ядра " + country.getDisplayName() + " вне арены."));
				return 0;
			}
		}

		ArenaCoreBuilder.buildAllInactive(level, center);
		source.sendSuccess(() -> Component.literal("Ядра четырёх стран построены."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coresStatus(CommandSourceStack source) {
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(source.getServer());
		BlockPos center = setup != null && setup.isConfigured() ? setup.getCenter() : null;
		String text = ArenaCoreManager.get().buildStatusText(source.getServer(), center);
		source.sendSuccess(() -> Component.literal(text), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coreDamage(CommandContext<CommandSourceStack> context) {
		Country country = Country.byId(StringArgumentType.getString(context, "country"));
		if (country == null) {
			context.getSource().sendFailure(Component.literal("Неизвестная страна. Используй: ru, ua, kz, by"));
			return 0;
		}

		float amount = FloatArgumentType.getFloat(context, "amount");
		ArenaCoreState state = ArenaCoreManager.get().getState(country);
		if (!state.isActive()) {
			context.getSource().sendFailure(Component.literal("Ядро " + country.getDisplayName() + " не активно."));
			return 0;
		}

		float before = state.getCurrentHealth();
		float after = ArenaCoreManager.get().damage(context.getSource().getServer(), country, amount);
		float percent = ArenaCoreManager.get().getState(country).getHealthPercent();

		context.getSource().sendSuccess(() -> Component.literal(
				country.getDisplayName() + ": HP "
						+ ArenaCoreManager.formatHealth(before) + " → "
						+ ArenaCoreManager.formatHealth(after)
						+ " (" + ArenaCoreManager.formatPercent(percent) + "%)"), false);

		if (after <= 0.0F) {
			ArenaCoreCombatManager.get().clearCoreTargetsForCountry(country);
			if (!ArenaCoreRescueManager.get().isRescuing(country)) {
				context.getSource().sendSuccess(() -> Component.literal(
						country.getDisplayName() + ": ядро разрушено."), false);
			}
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int coreHeal(CommandContext<CommandSourceStack> context) {
		Country country = Country.byId(StringArgumentType.getString(context, "country"));
		if (country == null) {
			context.getSource().sendFailure(Component.literal("Неизвестная страна. Используй: ru, ua, kz, by"));
			return 0;
		}

		float amount = FloatArgumentType.getFloat(context, "amount");
		ArenaCoreState state = ArenaCoreManager.get().getState(country);
		if (!state.isActive() && !state.isDestroyed()) {
			context.getSource().sendFailure(Component.literal(
					"Ядро " + country.getDisplayName() + " неактивно."));
			return 0;
		}

		float before = state.getCurrentHealth();
		float after = ArenaCoreManager.get().heal(context.getSource().getServer(), country, amount);
		float percent = ArenaCoreManager.get().getState(country).getHealthPercent();

		context.getSource().sendSuccess(() -> Component.literal(
				country.getDisplayName() + ": HP "
						+ ArenaCoreManager.formatHealth(before) + " → "
						+ ArenaCoreManager.formatHealth(after)
						+ " (" + ArenaCoreManager.formatPercent(percent) + "%)"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int coresReset(CommandSourceStack source) {
		ArenaCoreCombatManager.get().clearAll(source.getServer());
		ArenaCoreRescueManager.get().clearAll();
		ArenaCoreManager.get().resetRound(source.getServer());
		source.sendSuccess(() -> Component.literal("Состояния ядер сброшены. Все ядра INACTIVE."), false);
		return Command.SINGLE_SUCCESS;
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
