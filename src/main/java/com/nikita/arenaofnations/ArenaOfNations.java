package com.nikita.arenaofnations;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArenaOfNations implements ModInitializer {
	public static final String MOD_ID = "arena_of_nations";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_test")
					.executes(context -> {
						context.getSource().sendSuccess(() -> Component.literal("Arena of Nations работает!"), false);
						return 1;
					}));

			dispatcher.register(Commands.literal("arena_duel")
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						ServerLevel level = player.serverLevel();
						Vec3 pos = player.position();

						EntityType.ZOMBIE.spawn(level, BlockPos.containing(pos.x + 2.0, pos.y, pos.z), MobSpawnType.COMMAND);
						EntityType.IRON_GOLEM.spawn(level, BlockPos.containing(pos.x - 2.0, pos.y, pos.z), MobSpawnType.COMMAND);

						return 1;
					}));
		});

		ArenaTeamDuel.register();
		ArenaMatchManager.register();
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
