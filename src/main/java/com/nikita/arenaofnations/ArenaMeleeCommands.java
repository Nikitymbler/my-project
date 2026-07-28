package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class ArenaMeleeCommands {
	private ArenaMeleeCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("arena_melee_status")
						.requires(source -> source.hasPermission(2))
						.executes(ArenaMeleeCommands::meleeStatus)));
	}

	private static int meleeStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String text = ArenaMeleeDiagnostics.buildStatusText(source.getServer(), source.getLevel());
		source.sendSuccess(() -> Component.literal(text), false);
		return Command.SINGLE_SUCCESS;
	}
}
