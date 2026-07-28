package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Geometry helpers for the large circular arena (build version 2 — 20 physical bases).
 */
public final class ArenaPositions {
	public static final int CENTER_PATTERN_RADIUS = ArenaCountryBaseLayout.CENTER_PATTERN_RADIUS;
	public static final int COMBAT_WALKABLE_RADIUS = ArenaCountryBaseLayout.COMBAT_WALKABLE_RADIUS;
	/** @deprecated use {@link #CENTER_PATTERN_RADIUS} or {@link #COMBAT_WALKABLE_RADIUS}. */
	@Deprecated
	public static final int FIELD_RADIUS = ArenaCountryBaseLayout.FIELD_RADIUS;
	public static final int OUTER_RADIUS = ArenaCountryBaseLayout.OUTER_WALL_RADIUS;
	public static final int CLEAR_RADIUS = ArenaCountryBaseLayout.CLEAR_RADIUS;
	public static final int WALL_HEIGHT = 12;
	public static final int MAX_DECOR_HEIGHT = 16;
	public static final int CLEAR_HEIGHT = 17;
	public static final int FOUNDATION_DEPTH = 3;
	/** @deprecated use {@link ArenaCountryBaseLayout#SPAWN_RING_RADIUS} */
	@Deprecated
	public static final int SPAWN_DISTANCE = ArenaCountryBaseLayout.SPAWN_RING_RADIUS;
	/** @deprecated use {@link ArenaCountryBaseLayout#CORE_RING_RADIUS} */
	@Deprecated
	public static final int CORE_DISTANCE = ArenaCountryBaseLayout.CORE_RING_RADIUS;
	/** @deprecated use {@link ArenaCountryBaseLayout#CORE_ATTACK_RING_RADIUS} */
	@Deprecated
	public static final int CORE_ATTACK_DISTANCE = ArenaCountryBaseLayout.CORE_ATTACK_RING_RADIUS;
	public static final int SECTOR_RADIUS = 5;
	public static final int SPAWN_SCATTER = 3;
	public static final int CENTER_DECOR_RADIUS = 5;

	private ArenaPositions() {
	}

	public static BlockPos getCenter(ArenaSetupSavedData setup) {
		return setup.getCenter();
	}

	public static BlockPos getCountryBase(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.spawnBase(center, slot);
	}

	public static BlockPos getCountryBase(BlockPos center, int slot) {
		return ArenaCountryBaseLayout.spawnBase(center, slot);
	}

	public static BlockPos getCountryPortal(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.portalPosition(center, slot);
	}

	public static BlockPos getCorePosition(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			ArenaOfNations.LOGGER.warn("getCorePosition without assigned slot for {}", country.getId());
			return center.above();
		}
		return ArenaCountryBaseLayout.visualCorePosition(center, slot);
	}

	public static BlockPos getCorePosition(BlockPos center, int slot) {
		return ArenaCountryBaseLayout.visualCorePosition(center, slot);
	}

	public static BlockPos getVisualCorePosition(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.visualCorePosition(center, slot);
	}

	public static BlockPos getCoreDamagePosition(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.coreDamagePosition(center, slot);
	}

	public static BlockPos getCoreApproachPosition(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.coreApproachPosition(center, slot);
	}

	public static BlockPos resolveCoreApproachPosition(ServerLevel level, BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.resolveCoreApproachPosition(level, center, slot);
	}

	public static BlockPos getCoreAttackPosition(BlockPos center, Country country) {
		return getCoreApproachPosition(center, country);
	}

	public static BlockPos getCorePedestalCenter(BlockPos center, Country country) {
		return getCorePosition(center, country).below();
	}

	public static boolean isInsideArena(BlockPos center, BlockPos pos) {
		return distanceSqHorizontal(center, pos.getX(), pos.getZ()) <= (long) OUTER_RADIUS * OUTER_RADIUS;
	}

	public static boolean isCoreInsideArena(BlockPos center, Country country) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return false;
		}
		return isInsideArena(center, getCorePosition(center, slot));
	}

	public static BlockPos getSafeSpawn(ServerLevel level, BlockPos center, Country country, int fighterIndex) {
		int slot = resolveSlot(country);
		if (slot < 0) {
			return center.above();
		}
		return ArenaCountryBaseLayout.resolveSpawnPoint(level, center, slot, fighterIndex);
	}

	public static BlockPos getSafeSpawn(ServerLevel level, BlockPos center, int slot, int fighterIndex) {
		return ArenaCountryBaseLayout.resolveSpawnPoint(level, center, slot, fighterIndex);
	}

	public static boolean isInsideCombatWalkable(BlockPos center, BlockPos pos) {
		long dx = (long) pos.getX() - center.getX();
		long dz = (long) pos.getZ() - center.getZ();
		return dx * dx + dz * dz <= (long) COMBAT_WALKABLE_RADIUS * COMBAT_WALKABLE_RADIUS;
	}

	public static boolean isInsideField(BlockPos center, BlockPos pos) {
		return isInsideCombatWalkable(center, pos);
	}

	public static boolean isValidSpawn(ServerLevel level, BlockPos center, BlockPos feet) {
		if (!isInsideField(center, feet)) {
			return false;
		}

		BlockPos below = feet.below();
		if (!level.getBlockState(below).blocksMotion()) {
			return false;
		}
		if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
			return false;
		}

		AABB box = new AABB(
				feet.getX() + 0.1, feet.getY(), feet.getZ() + 0.1,
				feet.getX() + 0.9, feet.getY() + 1.8, feet.getZ() + 0.9);
		return level.noCollision(box);
	}

	public static double distanceSqHorizontal(BlockPos a, int x, int z) {
		long dx = (long) x - a.getX();
		long dz = (long) z - a.getZ();
		return dx * dx + dz * dz;
	}

	private static int resolveSlot(Country country) {
		int slot = ArenaMatchManager.get().getBaseSlot(country);
		if (slot < 0) {
			ArenaOfNations.LOGGER.warn("resolveSlot without assigned slot for {}", country.getId());
		}
		return slot;
	}
}
