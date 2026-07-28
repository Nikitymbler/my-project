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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
		String report = FighterTargeting.buildAiStatus(context.getSource().getLevel());
		context.getSource().sendSuccess(() -> Component.literal(report), false);
		return 1;
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
