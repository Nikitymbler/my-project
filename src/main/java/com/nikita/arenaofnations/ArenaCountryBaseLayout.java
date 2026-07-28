package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Round-stable country base slots around the arena (20 positions, 18° apart).
 * Physical arena v3/v4: gate fortresses, spawn platforms between base and center.
 *
 * <p>Adjacent center distance at {@link #CORE_RING_RADIUS}: {@code 2 × 67 × sin(9°) ≈ 20.9} blocks.
 * Base v4 footprint {@link #BASE_STRUCTURE_WIDTH}×{@link #BASE_STRUCTURE_DEPTH} + {@link #MIN_BASE_GAP_BLOCKS} gap.
 */
public final class ArenaCountryBaseLayout {
	public static final int MAX_ACTIVE_COUNTRIES = 20;
	public static final int BASE_SLOT_COUNT = 20;
	public static final double BASE_ANGLE_STEP_DEGREES = 360.0D / BASE_SLOT_COUNT;

	/** Decorative center pattern radius (visual only). */
	public static final int CENTER_PATTERN_RADIUS = 42;
	/** Full walkable combat radius (used by spawn/path validation). */
	public static final int COMBAT_WALKABLE_RADIUS = 64;
	/** @deprecated use {@link #CENTER_PATTERN_RADIUS} or {@link #COMBAT_WALKABLE_RADIUS}. */
	@Deprecated
	public static final int FIELD_RADIUS = CENTER_PATTERN_RADIUS;
	/** Spawn platform ring (fighters appear here). */
	public static final int SPAWN_ZONE_RADIUS = 52;
	public static final int SPAWN_RING_RADIUS = 54;
	/** @deprecated legacy ring — use {@link #coreApproachPosition} inward from core. */
	@Deprecated
	public static final int CORE_ATTACK_RING_RADIUS = 60;
	/** Default inward offset from visual core toward arena center (blocks). */
	public static final int CORE_APPROACH_INWARD_BLOCKS = 3;
	/** Main gate base / core ring. */
	public static final int CORE_RING_RADIUS = 67;
	public static final int PORTAL_EXTRA_OFFSET = 5;
	public static final int OUTER_WALL_RADIUS = 86;
	public static final int STANDS_INNER_RADIUS = 74;
	public static final int STANDS_OUTER_RADIUS = 82;
	public static final int CLEAR_RADIUS = 92;

	public static final int BASE_STRUCTURE_WIDTH = 13;
	public static final int BASE_STRUCTURE_DEPTH = 9;
	public static final int BASE_HEIGHT = 10;
	public static final int SPAWN_PLATFORM_WIDTH = 11;
	public static final int SPAWN_PLATFORM_DEPTH = 6;
	public static final int MIN_BASE_GAP_BLOCKS = 3;
	public static final int SPAWN_ZONE_POINT_COUNT = 10;
	public static final int SPAWN_ZONE_ROWS = 2;
	public static final int SPAWN_ZONE_COLS = 5;
	public static final int SPAWN_POINT_LATERAL_SPACING = 2;
	public static final int MIN_SAFE_SPAWN_POINTS = 8;
	public static final int SECTOR_RADIUS = 4;

	private ArenaCountryBaseLayout() {
	}

	public static double adjacentBaseCenterDistance() {
		return 2.0D * CORE_RING_RADIUS * Math.sin(Math.toRadians(BASE_ANGLE_STEP_DEGREES * 0.5D));
	}

	public static double slotAngleDegrees(int slot) {
		int normalized = Math.floorMod(slot, BASE_SLOT_COUNT);
		return normalized * BASE_ANGLE_STEP_DEGREES;
	}

	public static double slotAngleRadians(int slot) {
		return Math.toRadians(slotAngleDegrees(slot));
	}

	public static BlockPos offsetFromCenter(BlockPos center, int slot, int radius) {
		double angle = slotAngleRadians(slot);
		int dx = (int) Math.round(Math.sin(angle) * radius);
		int dz = (int) Math.round(-Math.cos(angle) * radius);
		return center.offset(dx, 1, dz);
	}

	public static BlockPos corePosition(BlockPos center, int slot) {
		return offsetFromCenter(center, slot, CORE_RING_RADIUS);
	}

	public static BlockPos spawnBase(BlockPos center, int slot) {
		return offsetFromCenter(center, slot, SPAWN_RING_RADIUS);
	}

	public static BlockPos spawnZoneCenter(BlockPos center, int slot) {
		return offsetFromCenter(center, slot, SPAWN_ZONE_RADIUS);
	}

	/** Visible / logical center of the country core block. */
	public static BlockPos visualCorePosition(BlockPos center, int slot) {
		return corePosition(center, slot);
	}

	/** Same as visual core — damage is applied to the logical core at this block. */
	public static BlockPos coreDamagePosition(BlockPos center, int slot) {
		return corePosition(center, slot);
	}

	/**
	 * Preferred melee approach point: a few blocks inward from the core toward the arena center.
	 */
	public static BlockPos coreApproachPosition(BlockPos center, int slot) {
		BlockPos core = corePosition(center, slot);
		return core.relative(inwardDirection(slot), CORE_APPROACH_INWARD_BLOCKS);
	}

	/** @deprecated use {@link #coreApproachPosition}. */
	@Deprecated
	public static BlockPos coreAttackPosition(BlockPos center, int slot) {
		return coreApproachPosition(center, slot);
	}

	/**
	 * Finds a collision-free approach feet position near the ideal inward point.
	 * Searches small inward/lateral offsets before falling back to the nominal point.
	 */
	public static BlockPos resolveCoreApproachPosition(ServerLevel level, BlockPos center, int slot) {
		BlockPos core = corePosition(center, slot);
		Direction inward = inwardDirection(slot);
		Direction side = outwardDirection(slot).getClockWise();
		int[] inwardOffsets = {CORE_APPROACH_INWARD_BLOCKS, 2, 4, 3, 2};
		int[] lateralOffsets = {0, -1, 1, -2, 2};

		for (int inwardBlocks : inwardOffsets) {
			for (int lateral : lateralOffsets) {
				BlockPos column = core.relative(inward, inwardBlocks).relative(side, lateral);
				BlockPos feet = resolveFeetOnSurface(level, center, column);
				if (feet != null && isValidCoreApproach(level, center, slot, feet)) {
					return feet;
				}
			}
		}
		return coreApproachPosition(center, slot);
	}

	public static boolean isValidCoreApproach(ServerLevel level, BlockPos center, int slot, BlockPos feet) {
		if (!ArenaPositions.isInsideCombatWalkable(center, feet)) {
			return false;
		}
		if (!ArenaPositions.isValidSpawn(level, center, feet)) {
			return false;
		}
		BlockPos core = corePosition(center, slot);
		double dx = (feet.getX() + 0.5D) - (core.getX() + 0.5D);
		double dz = (feet.getZ() + 0.5D) - (core.getZ() + 0.5D);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.5D || horizontal > 5.5D) {
			return false;
		}
		return true;
	}

	public static BlockPos portalPosition(BlockPos center, int slot) {
		return offsetFromCenter(center, slot, SPAWN_RING_RADIUS + PORTAL_EXTRA_OFFSET);
	}

	public static BlockPos basePlatformCenter(BlockPos center, int slot) {
		return spawnZoneCenter(center, slot);
	}

	public static Direction outwardDirection(int slot) {
		double angle = slotAngleRadians(slot);
		double dx = Math.sin(angle);
		double dz = -Math.cos(angle);
		if (Math.abs(dx) > Math.abs(dz) + 0.01D) {
			return dx > 0.0D ? Direction.EAST : Direction.WEST;
		}
		return dz > 0.0D ? Direction.SOUTH : Direction.NORTH;
	}

	public static Direction inwardDirection(int slot) {
		return outwardDirection(slot).getOpposite();
	}

	/**
	 * Deterministic spawn-zone points: 2 rows × 5 columns on the cleared platform (inward of base).
	 */
	public static List<BlockPos> spawnZonePoints(BlockPos center, int slot) {
		List<BlockPos> points = new ArrayList<>(SPAWN_ZONE_POINT_COUNT);
		BlockPos zoneCenter = spawnZoneCenter(center, slot);
		Direction inward = inwardDirection(slot);
		Direction side = outwardDirection(slot).getClockWise();
		int[] inwardRows = {5, 4};
		for (int row = 0; row < SPAWN_ZONE_ROWS; row++) {
			for (int col = -2; col <= 2; col++) {
				int lateral = col * SPAWN_POINT_LATERAL_SPACING;
				BlockPos base = zoneCenter.relative(inward, inwardRows[row]);
				int sx = base.getX() + side.getStepX() * lateral;
				int sz = base.getZ() + side.getStepZ() * lateral;
				points.add(new BlockPos(sx, zoneCenter.getY(), sz));
			}
		}
		return points;
	}

	/** All feet positions that pass {@link ArenaPositions#isValidSpawn} for this slot. */
	public static List<BlockPos> collectSafeSpawnFeet(ServerLevel level, BlockPos center, int slot) {
		List<BlockPos> safe = new ArrayList<>();
		for (BlockPos point : spawnZonePoints(center, slot)) {
			BlockPos feet = resolveFeetOnSurface(level, center, point);
			if (feet != null && ArenaPositions.isValidSpawn(level, center, feet)) {
				safe.add(feet);
			}
		}
		return safe;
	}

	public static BlockPos resolveSpawnPoint(ServerLevel level, BlockPos center, int slot, int fighterIndex) {
		List<BlockPos> zonePoints = spawnZonePoints(center, slot);
		int start = Math.floorMod(fighterIndex, zonePoints.size());
		for (int attempt = 0; attempt < zonePoints.size(); attempt++) {
			BlockPos candidate = zonePoints.get(Math.floorMod(start + attempt, zonePoints.size()));
			BlockPos resolved = resolveFeetOnSurface(level, center, candidate);
			if (resolved != null && ArenaPositions.isValidSpawn(level, center, resolved)) {
				return resolved;
			}
		}
		for (int ring = 1; ring <= 3; ring++) {
			for (BlockPos candidate : zonePoints) {
				Direction inward = inwardDirection(slot);
				BlockPos shifted = candidate.relative(inward, ring);
				BlockPos resolved = resolveFeetOnSurface(level, center, shifted);
				if (resolved != null && ArenaPositions.isValidSpawn(level, center, resolved)) {
					return resolved;
				}
			}
		}
		BlockPos fallback = resolveFeetOnSurface(level, center, spawnZoneCenter(center, slot));
		return fallback != null ? fallback : spawnBase(center, slot);
	}

	public static BlockPos resolveFeetOnSurface(ServerLevel level, BlockPos arenaCenter, BlockPos column) {
		for (int dy = -2; dy <= 3; dy++) {
			BlockPos feet = column.offset(0, dy, 0);
			if (ArenaPositions.isValidSpawn(level, arenaCenter, feet)) {
				return feet;
			}
		}
		return null;
	}

	/** Delegates to navigation-based path check (operator validation). */
	public static boolean hasWalkPathToCenter(ServerLevel level, BlockPos center, BlockPos spawnFeet) {
		return ArenaLayoutPathfinder.hasNavigationPathToCenter(level, center, spawnFeet);
	}

	public static int pickSlot(boolean[] occupied) {
		if (occupied == null || occupied.length != BASE_SLOT_COUNT) {
			throw new IllegalArgumentException("occupied must be length " + BASE_SLOT_COUNT);
		}
		int occupiedCount = 0;
		int first = -1;
		for (int slot = 0; slot < BASE_SLOT_COUNT; slot++) {
			if (occupied[slot]) {
				occupiedCount++;
				if (first < 0) {
					first = slot;
				}
			}
		}
		if (occupiedCount == 0) {
			return 0;
		}
		if (occupiedCount == 1) {
			return (first + BASE_SLOT_COUNT / 2) % BASE_SLOT_COUNT;
		}

		int bestSlot = -1;
		double bestMinDistance = -1.0D;
		for (int candidate = 0; candidate < BASE_SLOT_COUNT; candidate++) {
			if (occupied[candidate]) {
				continue;
			}
			double minDistance = minAngularSlotDistance(candidate, occupied);
			if (bestSlot < 0
					|| minDistance > bestMinDistance
					|| (Math.abs(minDistance - bestMinDistance) < 0.001D && candidate < bestSlot)) {
				bestSlot = candidate;
				bestMinDistance = minDistance;
			}
		}
		return bestSlot;
	}

	public static double minAngularSlotDistance(int candidate, boolean[] occupied) {
		double min = Double.MAX_VALUE;
		for (int slot = 0; slot < BASE_SLOT_COUNT; slot++) {
			if (!occupied[slot]) {
				continue;
			}
			double distance = angularDistanceDegrees(candidate, slot);
			if (distance < min) {
				min = distance;
			}
		}
		return min == Double.MAX_VALUE ? 0.0D : min;
	}

	public static double angularDistanceDegrees(int a, int b) {
		int diff = Math.abs(a - b) % BASE_SLOT_COUNT;
		return Math.min(diff, BASE_SLOT_COUNT - diff) * BASE_ANGLE_STEP_DEGREES;
	}

	public static Block primaryBlock(Country country) {
		return CountryVisualPalette.primaryBlock(country);
	}

	public static Block darkBlock(Country country) {
		return CountryVisualPalette.secondaryBlock(country);
	}

	public static Block glassBlock(Country country, boolean damaged) {
		if (damaged) {
			return Blocks.YELLOW_STAINED_GLASS;
		}
		Block primary = primaryBlock(country);
		if (primary == Blocks.RED_CONCRETE) {
			return Blocks.RED_STAINED_GLASS;
		}
		if (primary == Blocks.BLUE_CONCRETE) {
			return Blocks.BLUE_STAINED_GLASS;
		}
		if (primary == Blocks.GREEN_CONCRETE) {
			return Blocks.LIME_STAINED_GLASS;
		}
		if (primary == Blocks.CYAN_CONCRETE) {
			return Blocks.LIGHT_BLUE_STAINED_GLASS;
		}
		return Blocks.WHITE_STAINED_GLASS;
	}

	public static Block sectorBlock(Country country, int x, int z) {
		boolean alt = Math.floorMod(x + z, 2) == 0;
		return alt ? primaryBlock(country) : CountryVisualPalette.secondaryBlock(country);
	}

	public static Block neutralSectorBlock() {
		return Blocks.SMOOTH_STONE;
	}

	public static List<String> validateRoundLayout(
			BlockPos center,
			java.util.Map<Country, Integer> countrySlots) {
		List<String> errors = new ArrayList<>();
		if (countrySlots == null || countrySlots.isEmpty()) {
			return errors;
		}

		java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
		java.util.Set<BlockPos> corePositions = new java.util.HashSet<>();

		for (var entry : countrySlots.entrySet()) {
			Country country = entry.getKey();
			Integer slot = entry.getValue();
			if (slot == null || slot < 0 || slot >= BASE_SLOT_COUNT) {
				errors.add("invalid slot for " + country.getId() + ": " + slot);
				continue;
			}
			if (!usedSlots.add(slot)) {
				errors.add("duplicate base slot " + slot);
			}
			BlockPos core = corePosition(center, slot);
			if (!corePositions.add(core)) {
				errors.add("duplicate core position for slot " + slot);
			}
			if (!ArenaPositions.isInsideArena(center, core)) {
				errors.add("core outside arena for " + country.getId() + " slot " + slot);
			}
		}

		double minAllowed = BASE_STRUCTURE_WIDTH + MIN_BASE_GAP_BLOCKS;
		for (int left = 0; left < BASE_SLOT_COUNT; left++) {
			for (int right = left + 1; right < BASE_SLOT_COUNT; right++) {
				BlockPos a = corePosition(center, left);
				BlockPos b = corePosition(center, right);
				double distance = Math.sqrt(a.distSqr(b));
				if (distance < minAllowed) {
					errors.add(String.format(
							Locale.ROOT,
							"slots %d/%d too close (%.1f < %.1f)",
							left,
							right,
							distance,
							minAllowed));
				}
			}
		}
		return errors;
	}
}
