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
						context.getSource().sendSuccess(
								() -> Component.literal(
										ArenaFullCountryLifecycleTest.get().statusReport(context.getSource().getServer())),
								false);
						return Command.SINGLE_SUCCESS;
					}));
		});
	}
}
