package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

final class ArenaFighterRecordCommands {
	private ArenaFighterRecordCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_fighter_record_status")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						context.getSource().sendSuccess(
								() -> Component.literal(buildStatus(context.getSource().getServer())),
								false);
						return Command.SINGLE_SUCCESS;
					}));
			dispatcher.register(Commands.literal("arena_fighter_record_reset")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						ArenaStatsResetService.Result result = ArenaStatsResetService.reset(
								context.getSource().getServer(),
								ArenaStatsResetService.ResetType.FIGHTER_RECORD);
						context.getSource().sendSuccess(
								() -> Component.literal(result.message()),
								false);
						return result.success() ? Command.SINGLE_SUCCESS : 0;
					}));
		});
	}

	private static String buildStatus(MinecraftServer server) {
		Country country = ArenaScoreManager.getFighterRoundRecordCountry(server);
		int count = ArenaScoreManager.getFighterRoundRecordCount(server);
		StringBuilder sent = new StringBuilder();
		for (var entry : ArenaMatchManager.get().getFightersSentThisRoundSnapshot().entrySet()) {
			if (sent.length() > 0) {
				sent.append(',');
			}
			sent.append(entry.getKey().getCode()).append(':').append(entry.getValue());
		}
		return "Fighter round record:\n"
				+ "fighterRoundRecordCountry=" + (country == null ? "-" : country.getCode()) + '\n'
				+ "fighterRoundRecordCount=" + count + '\n'
				+ "fighterRoundRecordPersistent=true\n"
				+ "fighterRoundRecordStorage=SAVED_DATA\n"
				+ "currentRoundFightersSent=" + (sent.isEmpty() ? "-" : sent) + '\n'
				+ "statsResetAllowed=" + ArenaStatsResetService.isResetAllowed();
	}
}
