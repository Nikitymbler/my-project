package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Validates all 20 physical arena slots — strict: no false OK when spawns/path fail.
 */
public final class ArenaCountryLayoutValidator {
	public static final int MIN_SAFE_SPAWN_POINTS = 8;

	public record SlotReport(
			int slot,
			double angleDegrees,
			BlockPos baseCenter,
			BlockPos corePosition,
			BlockPos spawnCenter,
			int safeSpawnPoints,
			boolean floorValid,
			boolean collisionFree,
			boolean pathToCenter,
			boolean coreBuilt,
			boolean coreMatches,
			boolean valid,
			String issue) {
	}

	public record ValidationResult(
			boolean ok,
			List<String> errors,
			List<SlotReport> slots,
			int physicalBases,
			int validSlots,
			int invalidSlots,
			int totalSafeSpawnPoints,
			int slotsWithoutPath,
			int baseIntersections,
			int spawnIntersections,
			double adjacentBaseDistance) {
	}

	private ArenaCountryLayoutValidator() {
	}

	public static ValidationResult validateAllSlots(ServerLevel level, BlockPos center) {
		List<String> errors = new ArrayList<>();
		List<SlotReport> slots = new ArrayList<>(ArenaCountryBaseLayout.BASE_SLOT_COUNT);
		Set<BlockPos> corePositions = new HashSet<>();
		Set<Long> spawnZoneKeys = new HashSet<>();

		int validSlots = 0;
		int totalSafe = 0;
		int noPath = 0;
		int spawnIntersections = 0;

		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			BlockPos core = ArenaCountryBaseLayout.corePosition(center, slot);
			BlockPos spawnCenter = ArenaCountryBaseLayout.spawnZoneCenter(center, slot);
			BlockPos baseCenter = ArenaCountryBaseLayout.spawnBase(center, slot);

			List<BlockPos> safeFeet = ArenaCountryBaseLayout.collectSafeSpawnFeet(level, center, slot);
			int safePoints = safeFeet.size();
			totalSafe += safePoints;

			boolean floorValid = safePoints > 0;
			boolean collisionFree = safePoints >= MIN_SAFE_SPAWN_POINTS;
			boolean coreBuilt = isCoreBlock(level, core);
			boolean coreMatches = coreBuilt;

			boolean pathToCenter = !safeFeet.isEmpty();
			List<BlockPos> centerTargets = ArenaLayoutPathfinder.centralTargets(center);
			for (BlockPos feet : safeFeet) {
				if (!ArenaLayoutPathfinder.hasNavigationPathToAnyTarget(level, feet, centerTargets)) {
					pathToCenter = false;
					break;
				}
			}

			List<String> slotIssues = new ArrayList<>();
			if (!coreBuilt) {
				slotIssues.add("core missing");
			}
			if (safePoints < MIN_SAFE_SPAWN_POINTS) {
				slotIssues.add("safePts=" + safePoints + "<" + MIN_SAFE_SPAWN_POINTS);
			}
			if (!collisionFree && safePoints > 0) {
				spawnIntersections++;
			}
			if (!pathToCenter) {
				slotIssues.add("no path");
				noPath++;
			}
			if (!ArenaPositions.isInsideCombatWalkable(center, spawnCenter)) {
				slotIssues.add("spawn outside walkable field");
			}
			if (!ArenaPositions.isInsideArena(center, core)) {
				slotIssues.add("core outside arena");
			}
			boolean spawnInsideBase = isInsideBaseStructure(center, slot, safeFeet);
			if (spawnInsideBase) {
				slotIssues.add("spawn inside base");
			}

			boolean valid = coreBuilt
					&& coreMatches
					&& safePoints >= MIN_SAFE_SPAWN_POINTS
					&& pathToCenter
					&& !spawnInsideBase;

			if (!valid) {
				errors.add("slot " + slot + ": " + String.join(", ", slotIssues.isEmpty() ? List.of("invalid") : slotIssues));
			} else {
				validSlots++;
			}

			String issue = valid ? null : String.join("; ", slotIssues);

			corePositions.add(core);
			spawnZoneKeys.add(packXZ(spawnCenter));

			slots.add(new SlotReport(
					slot,
					ArenaCountryBaseLayout.slotAngleDegrees(slot),
					baseCenter,
					core,
					spawnCenter,
					safePoints,
					floorValid,
					collisionFree,
					pathToCenter,
					coreBuilt,
					coreMatches,
					valid,
					issue));
		}

		int baseIntersections = 0;
		double minAllowed = ArenaCountryBaseLayout.BASE_STRUCTURE_WIDTH + ArenaCountryBaseLayout.MIN_BASE_GAP_BLOCKS;
		for (int left = 0; left < ArenaCountryBaseLayout.BASE_SLOT_COUNT; left++) {
			for (int right = left + 1; right < ArenaCountryBaseLayout.BASE_SLOT_COUNT; right++) {
				BlockPos a = ArenaCountryBaseLayout.corePosition(center, left);
				BlockPos b = ArenaCountryBaseLayout.corePosition(center, right);
				double distance = Math.sqrt(a.distSqr(b));
				if (distance < minAllowed) {
					baseIntersections++;
					errors.add(String.format(
							Locale.ROOT,
							"overlap slots %d/%d distance %.1f < %.1f",
							left,
							right,
							distance,
							minAllowed));
				}
			}
		}

		if (corePositions.size() != ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			errors.add("unique core positions=" + corePositions.size() + " (expected 20)");
		}
		if (spawnZoneKeys.size() != ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			errors.add("unique spawn zones=" + spawnZoneKeys.size() + " (expected 20)");
		}

		boolean ok = validSlots == ArenaCountryBaseLayout.BASE_SLOT_COUNT && errors.isEmpty();

		return new ValidationResult(
				ok,
				errors,
				slots,
				ArenaCountryBaseLayout.BASE_SLOT_COUNT,
				validSlots,
				ArenaCountryBaseLayout.BASE_SLOT_COUNT - validSlots,
				totalSafe,
				noPath,
				baseIntersections,
				spawnIntersections,
				ArenaCountryBaseLayout.adjacentBaseCenterDistance());
	}

	private static boolean isCoreBlock(ServerLevel level, BlockPos core) {
		var state = level.getBlockState(core);
		return state.is(Blocks.LODESTONE) || state.is(Blocks.CRYING_OBSIDIAN) || state.blocksMotion();
	}

	private static boolean isInsideBaseStructure(
			BlockPos center,
			int slot,
			List<BlockPos> feetList) {
		BlockPos core = ArenaCountryBaseLayout.corePosition(center, slot);
		int halfW = ArenaCountryBaseLayout.BASE_STRUCTURE_WIDTH / 2;
		for (BlockPos feet : feetList) {
			if (feet.distSqr(core) < (long) halfW * halfW) {
				return true;
			}
		}
		return false;
	}

	private static long packXZ(BlockPos pos) {
		return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
	}
}
