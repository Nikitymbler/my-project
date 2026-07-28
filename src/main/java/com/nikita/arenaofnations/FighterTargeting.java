package com.nikita.arenaofnations;

import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Assigns living / core targets. Living-target chase belongs to {@link ArenaFighterMeleeAttackGoal}.
 */
final class FighterTargeting {
	private static final int CHECK_INTERVAL_TICKS = 5;
	/** Covers the full 36-radius field plus spawn scatter between opposite countries. */
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
					combat.clearCoreTarget(fighter.getUUID());
					fighter.getNavigation().stop();
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

	private static ArenaFighterEntity findNearestEnemy(ServerLevel level, ArenaFighterEntity self, Country selfCountry) {
		ArenaFighterEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity other) || other == self || !other.isAlive() || other.isRemoved()) {
				continue;
			}

			if (!FighterFactory.isArenaFighter(other)) {
				continue;
			}

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
		int total = 0;
		int withTarget = 0;
		int navigating = 0;
		int sitting = 0;
		int noAi = 0;
		StringBuilder details = new StringBuilder();

		ArenaCoreCombatManager combat = ArenaCoreCombatManager.get();
		BlockPos arenaCenter = ArenaCoreCombatManager.resolveArenaCenter(level.getServer());

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}

			total++;
			Country country = FighterFactory.getCountry(fighter);
			LivingEntity livingTarget = fighter.getTarget();
			boolean hasFighterTarget = livingTarget != null
					&& livingTarget.isAlive()
					&& !livingTarget.isRemoved()
					&& FighterFactory.isArenaFighter(livingTarget);
			Country coreTarget = combat.getCoreTarget(fighter.getUUID());
			boolean navActive = fighter.getNavigation().isInProgress() && !fighter.getNavigation().isDone();

			if (hasFighterTarget || coreTarget != null) {
				withTarget++;
			}
			if (navActive) {
				navigating++;
			}
			if (fighter.isOrderedToSit() || fighter.isInSittingPose()) {
				sitting++;
			}
			if (fighter.isNoAi()) {
				noAi++;
			}

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
			} else if (coreTarget != null) {
				details.append("ядро ").append(coreTarget.getDisplayName());
				if (arenaCenter != null) {
					BlockPos corePos = ArenaPositions.getCorePosition(arenaCenter, coreTarget);
					double dx = fighter.getX() - (corePos.getX() + 0.5);
					double dy = fighter.getY() - (corePos.getY() + 0.5);
					double dz = fighter.getZ() - (corePos.getZ() + 0.5);
					distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
						.append(String.format(java.util.Locale.US, "%.1f", distance))
						.append(" м)");
			}

			details.append(", nav=").append(navActive ? "да" : "нет");
			details.append(", у ядра=").append(inCoreRange ? "да" : "нет");
			details.append(", windup=").append(fighter.isMeleeWindupActive() ? "да" : "нет");
		}

		StringBuilder report = new StringBuilder();
		report.append("AI статус бойцов:\n");
		report.append("живые=").append(total).append('\n');
		report.append("с целью=").append(withTarget).append('\n');
		report.append("с навигацией=").append(navigating).append('\n');
		report.append("сидят=").append(sitting).append('\n');
		report.append("NoAI=").append(noAi);
		report.append(details);
		return report.toString();
	}
}