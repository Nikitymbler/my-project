package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class ArenaTeamDuel {
	private ArenaTeamDuel() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		FighterTargeting.register();
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena_team_duel")
				.executes(context -> startTeamDuel(context.getSource())));

		dispatcher.register(Commands.literal("arena_clear")
				.executes(context -> clearFighters(context.getSource())));

		dispatcher.register(Commands.literal("arena_spawn")
				.then(Commands.argument("country", StringArgumentType.word())
						.suggests(ArenaTeamDuel::suggestCountries)
						.executes(ArenaTeamDuel::spawnFighterCommand)));

		dispatcher.register(Commands.literal("arena_demo_four")
				.executes(context -> demoFour(context.getSource())));
	}

	private static int startTeamDuel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();
		Vec3 pos = player.position();

		ArenaSpawns.beginBatch();
		ArenaSpawns.Target russiaTarget = ArenaSpawns.resolve(source.getServer(), level, pos, Country.RU, 0);
		ArenaSpawns.Target ukraineTarget = ArenaSpawns.resolve(source.getServer(), level, pos, Country.UA, 0);
		if (russiaTarget == null || ukraineTarget == null) {
			source.sendFailure(Component.literal("Не удалось определить точки появления."));
			return 0;
		}

		ArenaFighterEntity russia = FighterFactory.create(
				russiaTarget.level(),
				russiaTarget.pos(),
				Country.RU,
				FighterTier.SCOUT);

		ArenaFighterEntity ukraine = FighterFactory.create(
				ukraineTarget.level(),
				ukraineTarget.pos(),
				Country.UA,
				FighterTier.SCOUT);

		if (russia == null || ukraine == null) {
			source.sendFailure(Component.literal("Не удалось создать бойцов."));
			return 0;
		}

		FighterTargeting.engage(russia, ukraine);
		FighterTargeting.engage(ukraine, russia);

		source.sendSuccess(() -> Component.literal("Командный бой начат: Россия vs Украина"), false);
		return 1;
	}

	private static int spawnFighterCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();

		Country country = Country.byId(StringArgumentType.getString(context, "country"));
		if (country == null) {
			source.sendFailure(Component.literal("Неизвестная страна. Используй: ru, ua, kz, by"));
			return 0;
		}

		ArenaSpawns.beginBatch();
		ArenaSpawns.Target target = ArenaSpawns.resolve(
				source.getServer(),
				player.serverLevel(),
				player.position(),
				country,
				0);
		if (target == null) {
			source.sendFailure(Component.literal("Не удалось определить точку появления."));
			return 0;
		}

		ArenaFighterEntity fighter = FighterFactory.create(target.level(), target.pos(), country, FighterTier.SCOUT);

		if (fighter == null) {
			source.sendFailure(Component.literal("Не удалось создать бойца."));
			return 0;
		}

		source.sendSuccess(
				() -> Component.literal("Создан боец: " + country.getDisplayName() + " — Боец"),
				false);
		return 1;
	}

	private static int demoFour(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();
		Vec3 pos = player.position();

		ArenaSpawns.beginBatch();
		Country[] countries = Country.values();
		ArenaFighterEntity[] fighters = new ArenaFighterEntity[countries.length];
		for (int i = 0; i < countries.length; i++) {
			ArenaSpawns.Target target = ArenaSpawns.resolve(source.getServer(), level, pos, countries[i], 0);
			if (target == null) {
				source.sendFailure(Component.literal("Не удалось создать всех разведчиков."));
				return 0;
			}
			fighters[i] = FighterFactory.create(target.level(), target.pos(), countries[i], FighterTier.SCOUT);
			if (fighters[i] == null) {
				source.sendFailure(Component.literal("Не удалось создать всех разведчиков."));
				return 0;
			}
		}

		source.sendSuccess(() -> Component.literal("Демо: четыре разведчика вокруг игрока"), false);
		return 1;
	}

	private static int clearFighters(CommandSourceStack source) {
		int removed = 0;

		for (ServerLevel level : source.getServer().getAllLevels()) {
			List<Entity> toRemove = new ArrayList<>();

			for (Entity entity : level.getAllEntities()) {
				if (FighterFactory.isArenaFighter(entity)) {
					toRemove.add(entity);
				}
			}

			for (Entity entity : toRemove) {
				entity.discard();
				removed++;
			}
		}

		int finalRemoved = removed;
		source.sendSuccess(() -> Component.literal("Удалено бойцов: " + finalRemoved), false);
		return removed;
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
