package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Operator-only particle overlay for arena base layout debugging.
 */
public final class ArenaCountryLayoutDebug {
	private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
	private static int tickCounter;

	private ArenaCountryLayoutDebug() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaCountryLayoutDebug::tick);
	}

	public static boolean isEnabled() {
		return ENABLED.get();
	}

	public static void setEnabled(boolean enabled) {
		ENABLED.set(enabled);
		tickCounter = 0;
	}

	private static void tick(MinecraftServer server) {
		if (!ENABLED.get()) {
			return;
		}
		tickCounter++;
		if (tickCounter % 10 != 0) {
			return;
		}

		ServerLevel level = ArenaSpawns.resolveFightLevel(server, server.overworld());
		if (level == null) {
			return;
		}

		BlockPos center = resolveCenter(server);
		spawnSlotParticles(level, center);
		spawnActiveCountryParticles(server, level, center);
	}

	private static BlockPos resolveCenter(MinecraftServer server) {
		Vec3 matchCenter = ArenaMatchManager.get().getMatchCenter();
		if (!matchCenter.equals(Vec3.ZERO)) {
			return BlockPos.containing(matchCenter.x, matchCenter.y, matchCenter.z);
		}
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null && setup.isConfigured()) {
			return setup.getCenter();
		}
		return server.overworld().getSharedSpawnPos();
	}

	private static void spawnSlotParticles(ServerLevel level, BlockPos center) {
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			BlockPos base = ArenaCountryBaseLayout.spawnBase(center, slot);
			spawn(level, base, ParticleTypes.END_ROD);

			BlockPos core = ArenaCountryBaseLayout.corePosition(center, slot);
			spawn(level, core, ParticleTypes.ENCHANT);

			for (BlockPos spawn : ArenaCountryBaseLayout.spawnZonePoints(center, slot)) {
				BlockPos feet = ArenaCountryBaseLayout.resolveFeetOnSurface(level, center, spawn);
				if (feet != null && ArenaPositions.isValidSpawn(level, center, feet)) {
					spawn(level, feet, ParticleTypes.HAPPY_VILLAGER);
					tracePath(level, center, feet);
				} else {
					spawn(level, spawn, ParticleTypes.FLAME);
				}
			}
		}
	}

	private static void spawnActiveCountryParticles(MinecraftServer server, ServerLevel level, BlockPos center) {
		for (Country country : ArenaMatchManager.get().getActiveCountries()) {
			int slot = ArenaMatchManager.get().getBaseSlot(country);
			if (slot < 0) {
				continue;
			}
			BlockPos core = ArenaCountryBaseLayout.corePosition(center, slot);
			spawn(level, core.above(2), ParticleTypes.ELECTRIC_SPARK);
		}
	}

	private static void tracePath(ServerLevel level, BlockPos center, BlockPos spawnFeet) {
		double targetX = center.getX() + 0.5D;
		double targetZ = center.getZ() + 0.5D;
		double startX = spawnFeet.getX() + 0.5D;
		double startZ = spawnFeet.getZ() + 0.5D;
		double dx = targetX - startX;
		double dz = targetZ - startZ;
		double length = Math.sqrt(dx * dx + dz * dz);
		int steps = Math.max(1, (int) Math.ceil(length));
		for (int step = 0; step <= steps; step++) {
			double t = step / (double) steps;
			double x = startX + dx * t;
			double y = spawnFeet.getY() + 0.2D;
			double z = startZ + dz * t;
			level.sendParticles(ParticleTypes.COMPOSTER, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	private static void spawn(ServerLevel level, BlockPos pos, net.minecraft.core.particles.ParticleOptions particle) {
		level.sendParticles(
				particle,
				pos.getX() + 0.5D,
				pos.getY() + 0.5D,
				pos.getZ() + 0.5D,
				2,
				0.1D,
				0.1D,
				0.1D,
				0.0D);
	}
}
