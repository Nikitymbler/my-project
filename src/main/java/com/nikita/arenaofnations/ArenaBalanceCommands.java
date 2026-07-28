package com.nikita.arenaofnations;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

final class ArenaBalanceCommands {
	private ArenaBalanceCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena_balance_status")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					String report = buildBalanceStatusText();
					context.getSource().sendSuccess(() -> Component.literal(report), false);
					return 1;
				}));

		dispatcher.register(Commands.literal("arena_economy_status")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					ServerLevel level = context.getSource().getLevel();
					String report = buildEconomyStatusText(level);
					context.getSource().sendSuccess(() -> Component.literal(report), false);
					return 1;
				}));

		dispatcher.register(Commands.literal("arena_swarm_status")
				.requires(source -> source.hasPermission(2))
				.executes(context -> {
					ServerLevel level = context.getSource().getLevel();
					String report = ArenaSwarmDamageProtection.buildSwarmStatusText(level);
					context.getSource().sendSuccess(() -> Component.literal(report), false);
					return 1;
				}));
	}

	static String buildBalanceStatusText() {
		StringBuilder builder = new StringBuilder("Arena balance v2 (economy + combat + swarm):\n");
		builder.append("Отказ от линейных весов 1/2/4/8/16 — стоимость подарка защищает дорогие tier.\n");
		builder.append("Economy test battle: ")
				.append(ArenaEconomyTest.BATTLE_SECONDS)
				.append(" секунд.\n");
		builder.append("Swarm budget окно: ")
				.append(ArenaSwarmDamageProtection.WINDOW_TICKS)
				.append(" тиков; gap rates 8% / 5% / 3% / 1.5% от MAX_HEALTH.\n");

		for (FighterTier tier : FighterTier.values()) {
			ArenaFighterBalance.TierBalance balance = ArenaFighterBalance.get(tier);
			builder.append('\n')
					.append(tier.name())
					.append(" (")
					.append(tier.getDisplayName())
					.append(")\n")
					.append("gift cost: ")
					.append(balance.giftCost())
					.append('\n')
					.append("effective combat value: ")
					.append(balance.effectiveCombatValue())
					.append('\n')
					.append("HP: ")
					.append(format(balance.maxHealth()))
					.append('\n')
					.append("damage: ")
					.append(format(balance.attackDamage()))
					.append('\n')
					.append("speed: ")
					.append(format(balance.movementSpeed()))
					.append('\n')
					.append("cooldown: ")
					.append(balance.attackCooldownTicks())
					.append(" ticks\n")
					.append("knockback resistance: ")
					.append(format(balance.knockbackResistance()))
					.append('\n')
					.append("ability: ")
					.append(balance.abilityName())
					.append('\n')
					.append("урон против более высоких tier:");

			boolean anyHigher = false;
			for (FighterTier defender : FighterTier.values()) {
				if (defender.ordinal() <= tier.ordinal()) {
					continue;
				}
				anyHigher = true;
				builder.append('\n')
						.append("  -> ")
						.append(defender.name())
						.append(": x")
						.append(formatMultiplier(ArenaTierDamageScaling.getDamageMultiplier(tier, defender)));
			}
			if (!anyHigher) {
				builder.append(" —");
			}
		}

		builder.append("\n\nДиагностика боя: /arena_economy_status, /arena_swarm_status");
		return builder.toString();
	}

	static String buildEconomyStatusText(ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		StringBuilder builder = new StringBuilder("Arena economy status:\n");
		builder.append("состояние=").append(match.getState());
		builder.append(", до конца=").append(match.getRemainingSeconds()).append("с\n");
		if (match.isEconomyTestBattle()) {
			builder.append("economy test battle=").append(ArenaEconomyTest.BATTLE_SECONDS).append("с\n");
		}

		for (Country country : Country.values()) {
			if (!match.getActiveCountries().contains(country)
					&& match.getReserveSize(country) <= 0
					&& match.countLivingFighters(level, country) <= 0) {
				continue;
			}
			ArenaEconomicArmyValue.CountryValue value = ArenaEconomicArmyValue.compute(level, country, match);
			builder.append('\n')
					.append(country.getDisplayName())
					.append('\n')
					.append("живые=").append(value.liveFighters())
					.append(", резерв=").append(value.reserveFighters())
					.append('\n')
					.append("gift value active=").append(format(value.activeGiftValue()))
					.append(", reserve=").append(format(value.reserveGiftValue()))
					.append(", total=").append(format(value.totalGiftValue()))
					.append('\n')
					.append("combat value active=").append(format(value.activeCombatValue()))
					.append(", reserve=").append(format(value.reserveCombatValue()))
					.append(", total=").append(format(value.totalCombatValue()))
					.append('\n')
					.append("урон=").append(format(match.getDamageDealt(country)));
		}

		return builder.toString();
	}

	private static String format(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	private static String formatMultiplier(float value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
