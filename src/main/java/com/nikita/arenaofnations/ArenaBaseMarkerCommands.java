package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Operator commands for world-space base markers (client-rendered).
 */
final class ArenaBaseMarkerCommands {
	private ArenaBaseMarkerCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_base_markers")
					.requires(source -> source.hasPermission(2))
					.then(Commands.literal("on").executes(context -> {
						ServerPlayer player = context.getSource().getPlayer();
						context.getSource().sendSuccess(
								() -> Component.literal(toggleHint(true, player)),
								false);
						return Command.SINGLE_SUCCESS;
					}))
					.then(Commands.literal("off").executes(context -> {
						ServerPlayer player = context.getSource().getPlayer();
						context.getSource().sendSuccess(
								() -> Component.literal(toggleHint(false, player)),
								false);
						return Command.SINGLE_SUCCESS;
					}))
					.then(Commands.literal("status").executes(context -> {
						context.getSource().sendSuccess(
								() -> Component.literal(buildServerStatus(context.getSource().getServer())),
								false);
						return Command.SINGLE_SUCCESS;
					}))
					.executes(context -> {
						context.getSource().sendSuccess(
								() -> Component.literal(buildServerStatus(context.getSource().getServer())),
								false);
						return Command.SINGLE_SUCCESS;
					}));
		});
	}

	private static String toggleHint(boolean on, ServerPlayer player) {
		String mode = on ? "ON" : "OFF";
		if (player != null) {
			return "Base markers are client-rendered. Run client command /arena_base_markers "
					+ (on ? "on" : "off")
					+ " (default ON). Requested=" + mode;
		}
		return "Base markers default ON (client). Requested=" + mode;
	}

	private static String buildServerStatus(net.minecraft.server.MinecraftServer server) {
		ArenaHudSnapshot snapshot = ArenaRoundHudSync.buildSnapshot(server);
		int withSlots = 0;
		for (ArenaHudCountryState row : snapshot.countries()) {
			if (row.baseSlot() >= 0) {
				withSlots++;
			}
		}
		return "Base markers (server sync):\n"
				+ "default=on (client toggle via /arena_base_markers)\n"
				+ "visibility=participant && !eliminated && baseSlot>=0\n"
				+ "active_markers=" + withSlots + '\n'
				+ "client_state_entries=" + snapshot.countries().size() + '\n'
				+ "arena_center_valid=" + snapshot.arenaCenterValid() + '\n'
				+ "max_render_distance=120\n"
				+ "legacy_textdisplay=disabled\n"
				+ "legacy_armorstand_labels=disabled";
	}
}
