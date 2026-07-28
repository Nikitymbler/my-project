package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Operator validation: real {@link Path} to arena center using a temporary probe mob.
 */
public final class ArenaLayoutPathfinder {
	public static final String TEST_PROBE_TAG = "arena_of_nations.layout_path_probe";
	private static final double ENDPOINT_TOLERANCE = 4.0D;

	private ArenaLayoutPathfinder() {
	}

	public enum PathFailureReason {
		NONE,
		INVALID_FEET,
		PROBE_CREATE_FAILED,
		NO_PATH,
		PATH_INCOMPLETE,
		ENDPOINT_OUTSIDE_TOLERANCE,
		EXCEPTION
	}

	public record PathCheckResult(boolean success, PathFailureReason reason, BlockPos targetUsed) {
		public static PathCheckResult ok(BlockPos target) {
			return new PathCheckResult(true, PathFailureReason.NONE, target);
		}

		public static PathCheckResult fail(PathFailureReason reason) {
			return new PathCheckResult(false, reason, null);
		}
	}

	public static boolean hasNavigationPathToCenter(ServerLevel level, BlockPos center, BlockPos feet) {
		if (level == null || feet == null || !ArenaPositions.isInsideCombatWalkable(center, feet)) {
			return false;
		}
		return hasNavigationPathToAnyTarget(level, feet, centralTargets(center));
	}

	public static boolean hasNavigationPathToAnyTarget(ServerLevel level, BlockPos feet, List<BlockPos> targets) {
		return checkNavigationPathToAnyTarget(level, feet, targets).success();
	}

	public static boolean hasNavigationPathToTarget(ServerLevel level, BlockPos feet, BlockPos target) {
		if (level == null || feet == null || target == null) {
			return false;
		}
		return checkNavigationPathToAnyTarget(level, feet, List.of(target)).success();
	}

	public static PathCheckResult checkNavigationPathToAnyTarget(ServerLevel level, BlockPos feet, List<BlockPos> targets) {
		if (level == null || feet == null || targets == null || targets.isEmpty()) {
			return PathCheckResult.fail(PathFailureReason.INVALID_FEET);
		}

		Mob probe = ArenaEntities.ARENA_FIGHTER.create(level);
		if (probe == null) {
			return PathCheckResult.fail(PathFailureReason.PROBE_CREATE_FAILED);
		}

		probe.addTag(TEST_PROBE_TAG);
		probe.setNoAi(false);
		probe.setSilent(true);
		probe.setInvulnerable(true);
		probe.setPos(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
		level.addFreshEntity(probe);

		try {
			for (BlockPos target : targets) {
				Path path = probe.getNavigation().createPath(target, 1);
				if (pathReachable(path, target)) {
					return PathCheckResult.ok(target);
				}
			}
			return PathCheckResult.fail(PathFailureReason.NO_PATH);
		} catch (Exception e) {
			ArenaOfNations.LOGGER.warn("Layout path probe failed at {}: {}", feet.toShortString(), e.toString());
			return PathCheckResult.fail(PathFailureReason.EXCEPTION);
		} finally {
			probe.discard();
		}
	}

	private static boolean pathReachable(Path path, BlockPos target) {
		if (path == null) {
			return false;
		}
		if (path.canReach()) {
			return true;
		}
		if (path.getEndNode() == null) {
			return false;
		}
		BlockPos end = new BlockPos(path.getEndNode().x, path.getEndNode().y, path.getEndNode().z);
		if (end.closerThan(target, ENDPOINT_TOLERANCE)) {
			return true;
		}
		return path.getNodeCount() > 1;
	}

	public static List<BlockPos> centralTargets(BlockPos center) {
		List<BlockPos> targets = new ArrayList<>(9);
		int y = 1;
		targets.add(center.above(y));
		targets.add(center.offset(4, y, 0));
		targets.add(center.offset(-4, y, 0));
		targets.add(center.offset(0, y, 4));
		targets.add(center.offset(0, y, -4));
		targets.add(center.offset(2, y, 0));
		targets.add(center.offset(-2, y, 0));
		targets.add(center.offset(0, y, 2));
		targets.add(center.offset(0, y, -2));
		return targets;
	}
}
