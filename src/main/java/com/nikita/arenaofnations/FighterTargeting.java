package com.nikita.arenaofnations;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Assigns living / core / rally targets. Living-target chase belongs to {@link ArenaFighterMeleeAttackGoal}.
 *
 * <p>Priority in BATTLE: (1) living enemy within {@link #SEARCH_RADIUS},
 * (2) unprotected enemy core, (3) rally toward nearest enemy front while cores are protected,
 * (4) idle only when no enemies remain.
 */
final class FighterTargeting {
	private static final int CHECK_INTERVAL_TICKS = 5;
	/**
	 * Local living acquisition radius. Opposite spawn zones are farther (~94–104);
	 * armies close the gap via rally until they enter this radius.
	 */
	private static final double SEARCH_RADIUS = 80.0;
	private static final double SEARCH_RADIUS_SQR = SEARCH_RADIUS * SEARCH_RADIUS;
	/** Keep a valid living target unless it leaves this radius. */
	private static final double TARGET_RETENTION_RADIUS = 14.0;
	private static final double TARGET_RETENTION_RADIUS_SQR = TARGET_RETENTION_RADIUS * TARGET_RETENTION_RADIUS;

	private FighterTargeting() {
	}

	static void register() {
		ServerTickEvents.END_WORLD_TICK.register(FighterTargeting::tickWorld);
	}

	static double getLivingSearchRadius() {
		return SEARCH_RADIUS;
	}

	private static void tickWorld(ServerLevel level) {
		if (!ArenaSpawns.isFightLevel(level)) {
			return;
		}

		ArenaCoreCombatManager combat = ArenaCoreCombatManager.get();
		combat.tickWindups(level);

		if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}

		combat.prune(level);

		boolean battle = ArenaMatchManager.get().getState() == ArenaMatchState.BATTLE;
		BlockPos arenaCenter = battle ? ArenaCoreCombatManager.resolveArenaCenter(level.getServer()) : null;
		Set<Country> activeCountries = battle ? ArenaMatchManager.get().getActiveCountries() : Set.of();

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}
			if (FighterFactory.isAiFrozen(fighter)) {
				continue;
			}

			Country selfCountry = FighterFactory.getCountry(fighter);
			if (selfCountry == null) {
				continue;
			}

			ensureCombatReady(fighter);

			if (fighter.isMeleeWindupActive()) {
				continue;
			}

			LivingEntity currentTarget = fighter.getTarget();
			if (currentTarget != null && !isValidEnemyTarget(fighter, selfCountry, currentTarget)) {
				clearLivingTarget(fighter);
				fighter.getNavigation().stop();
				currentTarget = null;
			}

			if (shouldRetainLivingTarget(fighter, selfCountry, currentTarget)) {
				combat.clearCoreTarget(fighter.getUUID());
				continue;
			}

			ArenaFighterEntity preferred = null;
			if (battle && arenaCenter != null) {
				preferred = combat.findDefensePreferredTarget(level, fighter, selfCountry, arenaCenter);
				if (preferred != null && fighter.distanceToSqr(preferred) > SEARCH_RADIUS_SQR) {
					preferred = null;
				}
			}

			ArenaFighterEntity opponent = preferred != null ? preferred : findNearestEnemy(level, fighter, selfCountry);

			if (opponent != null) {
				combat.clearCoreTarget(fighter.getUUID());
				if (fighter.getTarget() != opponent) {
					engage(fighter, opponent);
				}
				continue;
			}

			if (battle && arenaCenter != null) {
				clearLivingTarget(fighter);
				Country coreTarget = combat.findNearestAttackableCore(
						level, fighter, selfCountry, arenaCenter, activeCountries);
				if (coreTarget != null) {
					combat.pursueCore(level, fighter, selfCountry, coreTarget, arenaCenter);
				} else {
					combat.rallyTowardEnemyFront(level, fighter, selfCountry, arenaCenter, activeCountries);
				}
				continue;
			}

			combat.clearCoreTarget(fighter.getUUID());

			if (needsNewTarget(fighter, selfCountry)) {
				fighter.setTarget(null);
				fighter.setPersistentAngerTarget(null);
				fighter.getNavigation().stop();
			}
		}
	}

	private static boolean shouldRetainLivingTarget(
			ArenaFighterEntity fighter,
			Country selfCountry,
			LivingEntity target) {
		if (!isValidEnemyTarget(fighter, selfCountry, target)) {
			return false;
		}
		return fighter.distanceToSqr(target) <= TARGET_RETENTION_RADIUS_SQR;
	}

	private static boolean isValidEnemyTarget(ArenaFighterEntity fighter, Country selfCountry, LivingEntity target) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}
		if (!(target instanceof ArenaFighterEntity enemy) || !FighterFactory.isArenaFighter(enemy)) {
			return false;
		}
		if (FighterFactory.isAiFrozen(enemy)) {
			return false;
		}
		Country targetCountry = FighterFactory.getCountry(enemy);
		if (targetCountry == null || targetCountry == selfCountry) {
			return false;
		}
		if (ArenaCoreRescueManager.get().isEliminated(targetCountry)) {
			return false;
		}
		if (fighter.distanceToSqr(target) > SEARCH_RADIUS_SQR) {
			return false;
		}
		return true;
	}

	private static void clearLivingTarget(ArenaFighterEntity fighter) {
		fighter.setTarget(null);
		fighter.setPersistentAngerTarget(null);
	}

	private static void ensureCombatReady(ArenaFighterEntity fighter) {
		if (FighterFactory.isAiFrozen(fighter)) {
			return;
		}
		if (fighter.isNoAi()) {
			fighter.setNoAi(false);
		}
		if (fighter.isOrderedToSit()) {
			fighter.setOrderedToSit(false);
		}
		if (fighter.isInSittingPose()) {
			fighter.setInSittingPose(false);
		}
	}

	private static boolean needsNewTarget(ArenaFighterEntity fighter, Country selfCountry) {
		return !isValidEnemyTarget(fighter, selfCountry, fighter.getTarget());
	}

	/**
	 * Spatial query within {@link #SEARCH_RADIUS} — avoids scanning all fighters (O(N²)).
	 */
	private static ArenaFighterEntity findNearestEnemy(ServerLevel level, ArenaFighterEntity self, Country selfCountry) {
		AABB box = self.getBoundingBox().inflate(SEARCH_RADIUS);
		List<ArenaFighterEntity> nearby = level.getEntitiesOfClass(
				ArenaFighterEntity.class,
				box,
				other -> other != self
						&& other.isAlive()
						&& !other.isRemoved()
						&& FighterFactory.isArenaFighter(other)
						&& !FighterFactory.isAiFrozen(other));

		ArenaFighterEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (ArenaFighterEntity other : nearby) {
			Country otherCountry = FighterFactory.getCountry(other);
			if (otherCountry == null || otherCountry == selfCountry) {
				continue;
			}
			if (ArenaCoreRescueManager.get().isEliminated(otherCountry)) {
				continue;
			}

			double distance = self.distanceToSqr(other);
			if (distance <= SEARCH_RADIUS_SQR && distance < nearestDistance) {
				nearestDistance = distance;
				nearest = other;
			}
		}

		return nearest;
	}

	static void engage(ArenaFighterEntity attacker, ArenaFighterEntity opponent) {
		ensureCombatReady(attacker);
		LivingEntity previous = attacker.getTarget();
		long gameTime = attacker.level().getGameTime();
		if (previous == null) {
			attacker.getMeleeStats().recordTargetAssignment();
			ArenaMeleeDiagnostics.onTargetAssignment(gameTime);
			attacker.setTargetAssignedGameTime(gameTime);
		} else if (previous != opponent) {
			attacker.getMeleeStats().recordTargetSwitch();
			ArenaMeleeDiagnostics.onTargetSwitch(gameTime);
			attacker.setTargetAssignedGameTime(gameTime);
		}
		attacker.setPersistentAngerTarget(opponent.getUUID());
		attacker.startPersistentAngerTimer();
		attacker.setTarget(opponent);
		attacker.setLastHurtByMob(opponent);
	}

	static String buildAiStatus(ServerLevel level) {
		MarchSnapshot snap = collectMarchSnapshot(level);
		StringBuilder report = new StringBuilder();
		report.append("AI статус бойцов:\n");
		report.append("живые=").append(snap.total).append('\n');
		report.append("living target=").append(snap.withLivingTarget).append('\n');
		report.append("core target=").append(snap.withCoreAttackTarget).append('\n');
		report.append("rally nav=").append(snap.withRally).append('\n');
		report.append("navigating=").append(snap.navigating).append('\n');
		report.append("без nav=").append(snap.withoutNavigation).append('\n');
		report.append("сидят=").append(snap.sitting).append('\n');
		report.append("NoAI=").append(snap.noAi).append('\n');
		report.append("living search radius=").append(String.format(Locale.US, "%.0f", SEARCH_RADIUS)).append('\n');
		report.append(snap.details);
		return report.toString();
	}

	static MarchSnapshot collectMarchSnapshot(ServerLevel level) {
		MarchSnapshot snap = new MarchSnapshot();
		ArenaCoreCombatManager combat = ArenaCoreCombatManager.get();
		BlockPos arenaCenter = ArenaCoreCombatManager.resolveArenaCenter(level.getServer());
		StringBuilder details = new StringBuilder();

		int detailLines = 0;
		final int maxDetailLines = 20;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}

			snap.total++;
			Country country = FighterFactory.getCountry(fighter);
			LivingEntity livingTarget = fighter.getTarget();
			boolean hasFighterTarget = livingTarget != null
					&& livingTarget.isAlive()
					&& !livingTarget.isRemoved()
					&& FighterFactory.isArenaFighter(livingTarget);
			Country coreTarget = combat.getCoreTarget(fighter.getUUID());
			boolean rallyOnly = combat.isRallyOnly(fighter.getUUID());
			boolean navActive = fighter.getNavigation().isInProgress() && !fighter.getNavigation().isDone();

			if (hasFighterTarget) {
				snap.withLivingTarget++;
			} else if (rallyOnly) {
				snap.withRally++;
			} else if (coreTarget != null) {
				snap.withCoreAttackTarget++;
			}
			if (navActive) {
				snap.navigating++;
			} else {
				snap.withoutNavigation++;
			}
			if (fighter.isOrderedToSit() || fighter.isInSittingPose()) {
				snap.sitting++;
			}
			if (fighter.isNoAi()) {
				snap.noAi++;
			}

			if (detailLines >= maxDetailLines) {
				continue;
			}
			detailLines++;

			details.append('\n')
					.append("- ")
					.append(country == null ? "?" : country.getDisplayName())
					.append(": цель=");

			double distance = -1.0;
			boolean inCoreRange = false;

			if (hasFighterTarget) {
				Country targetCountry = FighterFactory.getCountry(livingTarget);
				distance = Math.sqrt(fighter.distanceToSqr(livingTarget));
				details.append("боец ")
						.append(targetCountry == null ? "?" : targetCountry.getDisplayName());
			} else if (rallyOnly && coreTarget != null) {
				details.append("rally→").append(coreTarget.getDisplayName());
				if (arenaCenter != null) {
					BlockPos corePos = ArenaPositions.getCorePosition(arenaCenter, coreTarget);
					distance = Math.sqrt(distanceSqr(fighter, corePos));
				}
			} else if (coreTarget != null) {
				details.append("ядро ").append(coreTarget.getDisplayName());
				if (arenaCenter != null) {
					BlockPos corePos = ArenaPositions.getCorePosition(arenaCenter, coreTarget);
					distance = Math.sqrt(distanceSqr(fighter, corePos));
					inCoreRange = combat.isInCoreAttackRange(fighter, arenaCenter, coreTarget);
				}
			} else if (livingTarget != null && livingTarget.isAlive() && !livingTarget.isRemoved()) {
				details.append("не-боец");
				distance = Math.sqrt(fighter.distanceToSqr(livingTarget));
			} else {
				details.append("нет");
			}

			if (distance >= 0.0) {
				details.append(" (")
						.append(String.format(Locale.US, "%.1f", distance))
						.append(" м)");
			}

			details.append(", nav=").append(navActive ? "да" : "нет");
			details.append(", у ядра=").append(inCoreRange ? "да" : "нет");
			details.append(", windup=").append(fighter.isMeleeWindupActive() ? "да" : "нет");
		}

		if (snap.total > maxDetailLines) {
			details.append('\n').append("... и ещё ").append(snap.total - maxDetailLines).append(" бойцов");
		}

		snap.details = details.toString();
		return snap;
	}

	private static double distanceSqr(Entity entity, BlockPos pos) {
		double dx = entity.getX() - (pos.getX() + 0.5);
		double dy = entity.getY() - (pos.getY() + 0.5);
		double dz = entity.getZ() - (pos.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz;
	}

	static final class MarchSnapshot {
		int total;
		int withLivingTarget;
		int withCoreAttackTarget;
		int withRally;
		int navigating;
		int withoutNavigation;
		int sitting;
		int noAi;
		String details = "";
	}
}
