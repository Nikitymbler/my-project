package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Resolves fighter spawn positions for gifts/reserves using the built arena when available.
 */
final class ArenaSpawns {
	private static boolean warnedThisBatch;

	private ArenaSpawns() {
	}

	static void beginBatch() {
		warnedThisBatch = false;
	}

	record Target(ServerLevel level, BlockPos pos) {
	}

	static Target resolve(
			MinecraftServer server,
			ServerLevel fallbackLevel,
			Vec3 fallbackCenter,
			Country country,
			int fighterIndex) {
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null && setup.isConfigured() && setup.isBuilt()) {
			ServerLevel arenaLevel = ArenaBuildManager.resolveArenaLevel(server);
			if (arenaLevel == null) {
				return null;
			}
			BlockPos pos = ArenaPositions.getSafeSpawn(arenaLevel, setup.getCenter(), country, fighterIndex);
			return new Target(arenaLevel, pos);
		}

		warnFallbackOnce(server);
		BlockPos pos = temporarySpawn(fallbackCenter, country, fighterIndex);
		return new Target(fallbackLevel, pos);
	}

	static Vec3 resolveMatchCenter(MinecraftServer server, Vec3 fallback) {
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null && setup.isConfigured() && setup.isBuilt()) {
			BlockPos c = setup.getCenter();
			return new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
		}
		return fallback;
	}

	static ServerLevel resolveFightLevel(MinecraftServer server, ServerLevel fallback) {
		ServerLevel arena = ArenaBuildManager.resolveArenaLevel(server);
		return arena != null ? arena : fallback;
	}

	static boolean isFightLevel(ServerLevel level) {
		if (level == null || level.getServer() == null) {
			return false;
		}
		return level == resolveFightLevel(level.getServer(), level);
	}

	private static BlockPos temporarySpawn(Vec3 center, Country country, int index) {
		int slot = ArenaMatchManager.get().getBaseSlot(country);
		if (slot < 0) {
			slot = Math.floorMod(country.ordinal(), ArenaCountryBaseLayout.BASE_SLOT_COUNT);
		}
		BlockPos base = ArenaCountryBaseLayout.spawnBase(
				BlockPos.containing(center.x, center.y, center.z),
				slot);
		double angle = index * 0.35;
		int dx = (int) Math.round(Math.cos(angle) * (1 + index % 3));
		int dz = (int) Math.round(Math.sin(angle) * (1 + index % 3));
		return base.offset(dx, 0, dz);
	}

	private static void warnFallbackOnce(MinecraftServer server) {
		if (warnedThisBatch) {
			return;
		}
		warnedThisBatch = true;
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("Арена не построена. Используется временная точка появления."),
				false);
	}
}
