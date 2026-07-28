package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class ArenaLifecycleCommands {
	private ArenaLifecycleCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_lifecycle_status")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						var server = context.getSource().getServer();
						String report;
						if (ArenaMassDuelReserveTest.get().isRunning()) {
							report = ArenaMassDuelReserveTest.get().statusReport(server);
						} else {
							report = ArenaFullCountryLifecycleTest.get().statusReport(server);
							String mass = ArenaMassDuelReserveTest.get().statusReport(server);
							if (mass.contains("result=PASS") || mass.contains("result=FAIL")) {
								report = mass + "\n---\n" + report;
							}
						}
						String finalReport = report;
						context.getSource().sendSuccess(() -> Component.literal(finalReport), false);
						return Command.SINGLE_SUCCESS;
					}));
		});
	}
}
