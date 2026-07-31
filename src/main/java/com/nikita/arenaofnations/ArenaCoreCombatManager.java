package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

/**
 * Temporary BATTLE-only core attack state: virtual core targets, attack cooldowns,
 * approach navigation, and short-lived defense threat signals.
 */
public final class ArenaCoreCombatManager {
	private static final ArenaCoreCombatManager INSTANCE = new ArenaCoreCombatManager();

	private static final double CORE_ATTACK_RANGE = 3.5;
	private static final double CORE_ATTACK_RANGE_SQR = CORE_ATTACK_RANGE * CORE_ATTACK_RANGE;
	private static final int CORE_ATTACK_COOLDOWN_TICKS = 20;
	private static final int CORE_WINDUP_TICKS = LivingEntity.SWING_DURATION / 2;
	private static final int DEFENSE_THREAT_TICKS = 100;
	private static final double DEFENSE_RADIUS = 28.0;
	private static final double DEFENSE_RADIUS_SQR = DEFENSE_RADIUS * DEFENSE_RADIUS;
	private static final double APPROACH_ARRIVAL_SQR = 1.5 * 1.5;
	private static final double REPATH_OFFSET_SQR = 2.5 * 2.5;
	private static final double CHASE_SPEED = 1.2;

	private final Map<UUID, FighterCoreCombat> fighters = new HashMap<>();
	private final Map<Country, DefenseThreat> defenseThreats = new EnumMap<>(Country.class);

	private ArenaCoreCombatManager() {
	}

	public static ArenaCoreCombatManager get() {
		return INSTANCE;
	}

	public void clearAll(MinecraftServer server) {
		if (server != null) {
			for (UUID id : fighters.keySet()) {
				ArenaFighterEntity fighter = findFighter(server, id);
				if (fighter != null) {
					stopCoreNavigation(fighter);
				}
			}
		}
		fighters.clear();
		defenseThreats.clear();
	}

	public Country getCoreTarget(UUID fighterId) {
		FighterCoreCombat state = fighters.get(fighterId);
		return state == null ? null : state.coreTarget;
	}

	public boolean isInCoreAttackRange(ArenaFighterEntity fighter, BlockPos arenaCenter, Country coreCountry) {
		BlockPos core = ArenaPositions.getCorePosition(arenaCenter, coreCountry);
		return distanceSqrToBlock(fighter, core) <= CORE_ATTACK_RANGE_SQR;
	}

	/**
	 * Preferred living attacker that recently hit this country's core, if the defender
	 * is near their own core and the threat is still valid.
	 */
	public ArenaFighterEntity findDefensePreferredTarget(ServerLevel level, ArenaFighterEntity defender, Country selfCountry, BlockPos arenaCenter) {
		DefenseThreat threat = defenseThreats.get(selfCountry);
		if (threat == null) {
			return null;
		}

		long now = level.getGameTime();
		if (now > threat.expireGameTime) {
			defenseThreats.remove(selfCountry);
			return null;
		}

		BlockPos ownCore = ArenaPositions.getCorePosition(arenaCenter, selfCountry);
		if (distanceSqrToBlock(defender, ownCore) > DEFENSE_RADIUS_SQR) {
			return null;
		}

		Entity entity = level.getEntity(threat.attackerId);
		if (!(entity instanceof ArenaFighterEntity attacker)
				|| !attacker.isAlive()
				|| attacker.isRemoved()
				|| !FighterFactory.isArenaFighter(attacker)) {
			defenseThreats.remove(selfCountry);
			return null;
		}

		Country attackerCountry = FighterFactory.getCountry(attacker);
		if (attackerCountry == null || attackerCountry == selfCountry) {
			defenseThreats.remove(selfCountry);
			return null;
		}

		return attacker;
	}

	public Country findNearestAttackableCore(
			ServerLevel level,
			ArenaFighterEntity fighter,
			Country selfCountry,
			BlockPos arenaCenter,
			Set<Country> activeCountries) {
		return findNearestEnemyCore(level, fighter, selfCountry, arenaCenter, activeCountries, true);
	}

	/**
	 * Nearest active enemy core for march/rally. When {@code requireUnprotected} is false,
	 * protected cores are still valid destinations (movement only — no damage).
	 */
	public Country findNearestEnemyCore(
			ServerLevel level,
			ArenaFighterEntity fighter,
			Country selfCountry,
			BlockPos arenaCenter,
			Set<Country> activeCountries,
			boolean requireUnprotected) {
		Country nearest = null;
		double nearestDist = Double.MAX_VALUE;
		ArenaCoreManager coreManager = ArenaCoreManager.get();

		for (Country country : activeCountries) {
			if (country == selfCountry) {
				continue;
			}

			ArenaCoreState state = coreManager.getState(country);
			if (!state.isActive() || state.isDestroyed() || ArenaCoreRescueManager.get().isEliminated(country)) {
				continue;
			}
			if (requireUnprotected && coreManager.isCoreProtected(level, country)) {
				continue;
			}

			BlockPos core = ArenaPositions.getCorePosition(arenaCenter, country);
			double dist = distanceSqrToBlock(fighter, core);
			if (dist < nearestDist) {
				nearestDist = dist;
				nearest = country;
			}
		}

		return nearest;
	}

	/**
	 * March toward a mid-field rally point while enemy cores are protected.
	 * Does not start core windup/damage — living melee takes over when armies meet.
	 * Rally sits near arena center, biased toward the nearest enemy approach, so opposite
	 * armies converge instead of swapping bases.
	 */
	public void rallyTowardEnemyFront(
			ServerLevel level,
			ArenaFighterEntity fighter,
			Country selfCountry,
			BlockPos arenaCenter,
			Set<Country> activeCountries) {
		Country enemyCore = findNearestEnemyCore(level, fighter, selfCountry, arenaCenter, activeCountries, false);
		if (enemyCore == null) {
			clearCoreTarget(fighter.getUUID());
			fighter.getNavigation().stop();
			return;
		}

		// Unprotected core → normal attack pursuit (priority 2).
		if (!ArenaCoreManager.get().isCoreProtected(level, enemyCore)) {
			pursueCore(level, fighter, selfCountry, enemyCore, arenaCenter);
			return;
		}

		FighterCoreCombat state = fighters.computeIfAbsent(fighter.getUUID(), id -> new FighterCoreCombat());
		boolean targetChanged = state.coreTarget != enemyCore || !state.rallyOnly;
		state.coreTarget = enemyCore;
		state.rallyOnly = true;
		state.windupTicksRemaining = 0;
		state.windupCoreTarget = null;
		state.lastWaitReason = "rally to mid-field";

		BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, arenaCenter, enemyCore);
		BlockPos rallyPos = midFieldRallyPoint(arenaCenter, approach, fighter.getBlockY());
		ensureNavigatingToCore(level, fighter, rallyPos, rallyPos, state, targetChanged);
	}

	/** ~12 blocks from center toward the enemy approach — armies meet in the middle. */
	private static BlockPos midFieldRallyPoint(BlockPos arenaCenter, BlockPos enemyApproach, int y) {
		double cx = arenaCenter.getX() + 0.5;
		double cz = arenaCenter.getZ() + 0.5;
		double ax = enemyApproach.getX() + 0.5;
		double az = enemyApproach.getZ() + 0.5;
		double dx = ax - cx;
		double dz = az - cz;
		double len = Math.sqrt(dx * dx + dz * dz);
		double bias = 12.0;
		if (len > 1.0E-3) {
			dx = dx / len * bias;
			dz = dz / len * bias;
		} else {
			dx = 0.0;
			dz = 0.0;
		}
		return BlockPos.containing(cx + dx, y, cz + dz);
	}

	public boolean isRallyOnly(UUID fighterId) {
		FighterCoreCombat state = fighters.get(fighterId);
		return state != null && state.rallyOnly && state.coreTarget != null;
	}

	public void clearCoreTarget(UUID fighterId) {
		FighterCoreCombat state = fighters.get(fighterId);
		if (state != null) {
			state.coreTarget = null;
			state.rallyOnly = false;
			state.lastMoveTarget = null;
			state.windupTicksRemaining = 0;
			state.windupCoreTarget = null;
			if (state.nextAttackGameTime <= 0L) {
				fighters.remove(fighterId);
			}
		}
	}

	public void clearCoreTargetsForCountry(Country coreCountry) {
		Iterator<Map.Entry<UUID, FighterCoreCombat>> it = fighters.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, FighterCoreCombat> entry = it.next();
			FighterCoreCombat state = entry.getValue();
			if (state.coreTarget == coreCountry || state.windupCoreTarget == coreCountry) {
				state.coreTarget = null;
				state.rallyOnly = false;
				state.lastMoveTarget = null;
				state.windupTicksRemaining = 0;
				state.windupCoreTarget = null;
				if (state.nextAttackGameTime <= 0L) {
					it.remove();
				}
			}
		}
	}

	/**
	 * Drop core targets and defense signals tied to an eliminated country.
	 */
	public void onCountryEliminated(MinecraftServer server, Country country) {
		clearCoreTargetsForCountry(country);
		defenseThreats.remove(country);

		if (server != null) {
			defenseThreats.entrySet().removeIf(entry -> {
				ArenaFighterEntity attacker = findFighter(server, entry.getValue().attackerId);
				return attacker != null && FighterFactory.getCountry(attacker) == country;
			});

			for (ServerLevel level : server.getAllLevels()) {
				for (Entity entity : level.getAllEntities()) {
					if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter)) {
						continue;
					}
					LivingEntity target = fighter.getTarget();
					if (target != null && FighterFactory.getCountry(target) == country) {
						fighter.setTarget(null);
						fighter.setPersistentAngerTarget(null);
						fighter.getNavigation().stop();
					}
					if (FighterFactory.getCountry(fighter) == country) {
						clearCoreTarget(fighter.getUUID());
						fighter.getNavigation().stop();
					}
				}
			}
		}
	}

	/**
	 * Assign virtual core target and navigate / strike when in range.
	 */
	public void pursueCore(ServerLevel level, ArenaFighterEntity fighter, Country selfCountry, Country coreCountry, BlockPos arenaCenter) {
		ArenaCoreState coreState = ArenaCoreManager.get().getState(coreCountry);
		if (!coreState.isActive() || coreState.isDestroyed()) {
			cancelCoreAttack(fighter, "missing core state");
			return;
		}
		if (ArenaCoreManager.get().isCoreProtected(level, coreCountry)) {
			cancelCoreAttack(fighter, "core protected");
			return;
		}

		FighterCoreCombat state = fighters.computeIfAbsent(fighter.getUUID(), id -> new FighterCoreCombat());
		boolean targetChanged = state.coreTarget != coreCountry || state.rallyOnly;
		state.coreTarget = coreCountry;
		state.rallyOnly = false;
		state.lastWaitReason = null;

		BlockPos corePos = ArenaPositions.getCoreDamagePosition(arenaCenter, coreCountry);
		BlockPos attackPos = ArenaPositions.resolveCoreApproachPosition(level, arenaCenter, coreCountry);
		boolean inAttackRange = distanceSqrToBlock(fighter, corePos) <= CORE_ATTACK_RANGE_SQR;

		if (inAttackRange) {
			fighter.getNavigation().stop();
			tryAttackCore(level, fighter, selfCountry, coreCountry, arenaCenter, state);
			return;
		}

		ensureNavigatingToCore(level, fighter, attackPos, corePos, state, targetChanged);
	}

	public void tickWindups(ServerLevel level) {
		if (!ArenaSpawns.isFightLevel(level)) {
			return;
		}
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return;
		}

		BlockPos arenaCenter = resolveArenaCenter(level.getServer());
		if (arenaCenter == null) {
			return;
		}

		// Copy entries: executeCoreHit / cancelCoreAttack may structurally mutate `fighters`.
		List<Map.Entry<UUID, FighterCoreCombat>> windups = new ArrayList<>(fighters.entrySet());
		for (Map.Entry<UUID, FighterCoreCombat> entry : windups) {
			FighterCoreCombat state = fighters.get(entry.getKey());
			if (state == null) {
				continue;
			}
			if (state.rallyOnly) {
				state.windupTicksRemaining = 0;
				state.windupCoreTarget = null;
				continue;
			}
			if (state.windupTicksRemaining <= 0) {
				continue;
			}

			Entity entity = level.getEntity(entry.getKey());
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| fighter.isRemoved()
					|| !FighterFactory.isArenaFighter(fighter)) {
				state.windupTicksRemaining = 0;
				state.windupCoreTarget = null;
				continue;
			}

			Country coreCountry = state.windupCoreTarget;
			if (coreCountry == null) {
				state.windupTicksRemaining = 0;
				continue;
			}

			if (ArenaCoreManager.get().isCoreProtected(level, coreCountry)) {
				cancelCoreAttack(fighter);
				continue;
			}

			state.windupTicksRemaining--;
			if (state.windupTicksRemaining > 0) {
				continue;
			}

			Country selfCountry = FighterFactory.getCountry(fighter);
			executeCoreHit(level, fighter, selfCountry, coreCountry, arenaCenter, state);
		}
	}

	public int countAttackersTargeting(Country coreCountry) {
		if (coreCountry == null) {
			return 0;
		}
		int count = 0;
		for (FighterCoreCombat combat : fighters.values()) {
			if (combat.coreTarget == coreCountry || combat.windupCoreTarget == coreCountry) {
				count++;
			}
		}
		return count;
	}

	public void prune(ServerLevel level) {
		if (!ArenaSpawns.isFightLevel(level)) {
			return;
		}
		long now = level.getGameTime();

		defenseThreats.entrySet().removeIf(entry -> {
			DefenseThreat threat = entry.getValue();
			if (now > threat.expireGameTime) {
				return true;
			}
			Entity entity = level.getEntity(threat.attackerId);
			return !(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| fighter.isRemoved()
					|| !FighterFactory.isArenaFighter(fighter);
		});

		Iterator<Map.Entry<UUID, FighterCoreCombat>> it = fighters.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, FighterCoreCombat> entry = it.next();
			Entity entity = level.getEntity(entry.getKey());
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| fighter.isRemoved()
					|| !FighterFactory.isArenaFighter(fighter)) {
				it.remove();
				continue;
			}

			Country target = entry.getValue().coreTarget;
			if (target != null) {
				ArenaCoreState core = ArenaCoreManager.get().getState(target);
				if (!core.isActive() || core.isDestroyed()) {
					entry.getValue().coreTarget = null;
					entry.getValue().lastMoveTarget = null;
				}
			}

			if (entry.getValue().coreTarget == null && entry.getValue().nextAttackGameTime <= now) {
				it.remove();
			}
		}
	}

	public String buildCombatStatusText(MinecraftServer server, ServerLevel level) {
		if (!ArenaSpawns.isFightLevel(level)) {
			ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
			if (fightLevel != null && fightLevel != level) {
				return buildCombatStatusText(server, fightLevel);
			}
		}

		ArenaMatchManager match = ArenaMatchManager.get();
		BlockPos arenaCenter = resolveArenaCenter(server);
		long now = level.getGameTime();

		int fighterTargets = 0;
		int coreTargets = 0;
		int nearCore = 0;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}

			if (fighter.getTarget() != null && fighter.getTarget().isAlive() && FighterFactory.isArenaFighter(fighter.getTarget())) {
				fighterTargets++;
			}

			Country coreTarget = getCoreTarget(fighter.getUUID());
			if (coreTarget != null) {
				coreTargets++;
			}

			if (arenaCenter != null) {
				for (Country country : Country.values()) {
					if (isInCoreAttackRange(fighter, arenaCenter, country)) {
						nearCore++;
						break;
					}
				}
			}
		}

		int activeThreats = 0;
		for (DefenseThreat threat : defenseThreats.values()) {
			if (now <= threat.expireGameTime) {
				activeThreats++;
			}
		}

		StringBuilder builder = new StringBuilder();
		builder.append("Статус боя ядер:\n");
		builder.append("состояние матча=").append(match.getState()).append('\n');
		builder.append("coreRegenEnabled=true\n");
		builder.append("coreRegenAmount=").append((int) ArenaCoreRegenMath.CORE_REGEN_AMOUNT).append('\n');
		builder.append("coreRegenIntervalTicks=").append(ArenaCoreRegenMath.CORE_REGEN_INTERVAL_TICKS).append('\n');
		builder.append("coreRegenDamageDelayTicks=").append(ArenaCoreRegenMath.CORE_REGEN_DAMAGE_DELAY_TICKS).append('\n');
		builder.append("бойцы с целью-бойцом=").append(fighterTargets).append('\n');
		builder.append("бойцы с целью-ядром=").append(coreTargets).append('\n');
		builder.append("бойцы рядом с ядром=").append(nearCore).append('\n');
		builder.append("активные сигналы защиты=").append(activeThreats).append('\n');
		builder.append("ядра:");

		for (Country country : Country.values()) {
			ArenaCoreState state = ArenaCoreManager.get().getState(country);
			int attackers = countAttackersTargeting(country);

			int defenders = ArenaCoreManager.get().countActiveDefenders(level, country);
			boolean protectedCore = ArenaCoreManager.get().isCoreProtected(level, country);
			boolean eliminated = ArenaCoreRescueManager.get().isEliminated(country);
			ArenaCoreRegenMath.Eval regen = ArenaCoreManager.get().evaluateRegen(country, now);

			builder.append('\n')
					.append("- ")
					.append(country.getDisplayName())
					.append(": active=").append(state.isActive())
					.append(", destroyed=").append(state.isDestroyed())
					.append(", hp=")
					.append(ArenaCoreManager.formatHealth(state.getCurrentHealth()))
					.append('/')
					.append(ArenaCoreManager.formatHealth(state.getMaxHealth()))
					.append(", защитников=").append(defenders)
					.append(", статус=").append(protectedCore ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
					.append(", атакуют=").append(attackers);

			if (match.getActiveCountries().contains(country) || eliminated) {
				builder.append('\n')
						.append("  regen: country=").append(country.getCode())
						.append(" coreHp=").append(Math.round(state.getCurrentHealth()))
						.append(" coreMaxHp=").append(Math.round(state.getMaxHealth()))
						.append(" eliminated=").append(eliminated)
						.append(" lastDamageGameTime=")
						.append(state.getLastCoreDamageGameTime() < 0L
								? "-"
								: String.valueOf(state.getLastCoreDamageGameTime()))
						.append(" lastRegenGameTime=")
						.append(state.getLastCoreRegenGameTime() < 0L
								? "-"
								: String.valueOf(state.getLastCoreRegenGameTime()))
						.append(" damageDelayRemainingTicks=").append(regen.damageDelayRemainingTicks())
						.append(" regenEligible=").append(regen.eligible())
						.append(" regenBlockedReason=").append(regen.reason().name());
			}
		}

		builder.append("\n\nбойцы с core target (первые 10):");
		int listed = 0;
		int withCoreTarget = 0;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}

			FighterCoreCombat combat = fighters.get(fighter.getUUID());
			if (combat == null || combat.coreTarget == null) {
				continue;
			}

			withCoreTarget++;
			if (listed >= 10) {
				continue;
			}

			appendFighterCoreDiagnostics(builder, level, fighter, combat, arenaCenter, now, listed + 1);
			listed++;
		}

		if (withCoreTarget == 0) {
			builder.append("\n(нет)");
		} else if (withCoreTarget > listed) {
			builder.append("\n... ещё ")
					.append(withCoreTarget - listed)
					.append(" бойцов с core target");
		}

		builder.append("\n\nпроблемные бойцы без core target (первые 5):");
		int problemListed = 0;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}
			FighterCoreCombat combat = fighters.get(fighter.getUUID());
			if (combat != null && combat.coreTarget != null) {
				continue;
			}
			LivingEntity livingTarget = fighter.getTarget();
			boolean staleDead = livingTarget != null && (!livingTarget.isAlive() || livingTarget.isRemoved());
			boolean enemyAlive = false;
			for (Country country : match.getActiveCountries()) {
				if (country != FighterFactory.getCountry(fighter)
						&& ArenaCoreManager.get().isCoreProtected(level, country)) {
					enemyAlive = true;
					break;
				}
			}
			if (!staleDead && !enemyAlive && livingTarget instanceof ArenaFighterEntity) {
				continue;
			}
			if (problemListed >= 5) {
				break;
			}
			builder.append('\n')
					.append(problemListed + 1)
					.append(". uuid=")
					.append(fighter.getUUID())
					.append(", country=")
					.append(FighterFactory.getCountry(fighter))
					.append(", target=")
					.append(formatLivingTarget(livingTarget))
					.append(", staleDead=")
					.append(staleDead)
					.append(", wait=")
					.append(combat == null ? diagnoseIdleWait(level, fighter, arenaCenter) : combat.lastWaitReason);
			problemListed++;
		}
		if (problemListed == 0) {
			builder.append("\n(нет)");
		}

		return builder.toString();
	}

	private void appendFighterCoreDiagnostics(
			StringBuilder builder,
			ServerLevel level,
			ArenaFighterEntity fighter,
			FighterCoreCombat combat,
			BlockPos arenaCenter,
			long now,
			int index) {
		Country attackerCountry = FighterFactory.getCountry(fighter);
		Country targetCoreCountry = combat.coreTarget;
		LivingEntity livingTarget = fighter.getTarget();
		boolean targetProtected = ArenaCoreManager.get().isCoreProtected(level, targetCoreCountry);
		ArenaCoreState coreState = ArenaCoreManager.get().getState(targetCoreCountry);
		BlockPos visualCore = arenaCenter == null ? null : ArenaPositions.getVisualCorePosition(arenaCenter, targetCoreCountry);
		BlockPos approach = arenaCenter == null ? null : ArenaPositions.resolveCoreApproachPosition(level, arenaCenter, targetCoreCountry);
		double distance = -1.0;
		double approachDist = -1.0;
		boolean inRange = false;
		boolean approachValid = false;
		boolean pathExists = false;
		if (arenaCenter != null && visualCore != null) {
			distance = Math.sqrt(distanceSqrToBlock(fighter, visualCore));
			inRange = isInCoreAttackRange(fighter, arenaCenter, targetCoreCountry);
			if (approach != null) {
				approachDist = Math.sqrt(distanceSqrToBlock(fighter, approach));
				int slot = ArenaMatchManager.get().getBaseSlot(targetCoreCountry);
				approachValid = slot >= 0 && ArenaCountryBaseLayout.isValidCoreApproach(level, arenaCenter, slot, approach);
				pathExists = ArenaLayoutPathfinder.hasNavigationPathToTarget(level, fighter.blockPosition(), approach);
			}
		}

		long cooldownTicks = Math.max(0L, combat.nextAttackGameTime - now);
		builder.append('\n')
				.append(index)
				.append(". uuid=")
				.append(fighter.getUUID())
				.append(", country=")
				.append(attackerCountry == null ? "?" : attackerCountry.getDisplayName())
				.append(", livingTarget=")
				.append(formatLivingTarget(livingTarget))
				.append(", coreTarget=")
				.append(targetCoreCountry.getDisplayName())
				.append(", enemyLiving=")
				.append(ArenaCoreManager.get().countActiveDefenders(level, targetCoreCountry))
				.append(", protected=")
				.append(targetProtected)
				.append(", coreExists=")
				.append(coreState.isActive() && !coreState.isDestroyed())
				.append(", visualCore=")
				.append(visualCore == null ? "?" : visualCore.toShortString())
				.append(", approach=")
				.append(approach == null ? "?" : approach.toShortString())
				.append(", approachValid=")
				.append(approachValid)
				.append(", path=")
				.append(pathExists)
				.append(", navDone=")
				.append(fighter.getNavigation().isDone())
				.append(", distCore=")
				.append(distance < 0.0 ? "?" : String.format(java.util.Locale.US, "%.1f", distance))
				.append(", distApproach=")
				.append(approachDist < 0.0 ? "?" : String.format(java.util.Locale.US, "%.1f", approachDist))
				.append(", inRange=")
				.append(inRange)
				.append(", windup=")
				.append(combat.windupTicksRemaining)
				.append(", cooldown=")
				.append(cooldownTicks)
				.append(", lastCancel=")
				.append(combat.lastCancelReason == null ? "-" : combat.lastCancelReason)
				.append(", wait=")
				.append(combat.lastWaitReason == null ? "-" : combat.lastWaitReason)
				.append(", lastCoreDamage=")
				.append(combat.lastCoreDamageGameTime < 0L ? "-" : String.valueOf(now - combat.lastCoreDamageGameTime));
	}

	private static String formatLivingTarget(LivingEntity target) {
		if (target == null) {
			return "none";
		}
		if (!(target instanceof ArenaFighterEntity enemy)) {
			return target.getType().getDescription().getString();
		}
		return "fighter:" + enemy.getArenaCountry() + (target.isAlive() ? "" : "(dead)");
	}

	private static String diagnoseIdleWait(ServerLevel level, ArenaFighterEntity fighter, BlockPos arenaCenter) {
		if (!ArenaSpawns.isFightLevel(level)) {
			return "outside fight level";
		}
		LivingEntity target = fighter.getTarget();
		if (target != null && (!target.isAlive() || target.isRemoved())) {
			return "stale dead target";
		}
		if (target instanceof ArenaFighterEntity) {
			return "enemy fighter alive";
		}
		if (arenaCenter == null) {
			return "missing arena center";
		}
		Country self = FighterFactory.getCountry(fighter);
		Country nearest = get().findNearestAttackableCore(
				level, fighter, self, arenaCenter, ArenaMatchManager.get().getActiveCountries());
		if (nearest == null) {
			return "no vulnerable core";
		}
		return "pending target assignment";
	}

	static BlockPos resolveArenaCenter(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null && setup.isConfigured()) {
			return setup.getCenter();
		}
		return null;
	}

	private void tryAttackCore(
			ServerLevel level,
			ArenaFighterEntity fighter,
			Country selfCountry,
			Country coreCountry,
			BlockPos arenaCenter,
			FighterCoreCombat state) {
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return;
		}
		if (!fighter.isAlive() || !FighterFactory.isArenaFighter(fighter)) {
			return;
		}
		if (selfCountry == null || selfCountry == coreCountry) {
			return;
		}
		if (ArenaCoreManager.get().isCoreProtected(level, coreCountry)) {
			cancelCoreAttack(fighter);
			return;
		}

		ArenaCoreState coreState = ArenaCoreManager.get().getState(coreCountry);
		if (!coreState.isActive() || coreState.isDestroyed()) {
			cancelCoreAttack(fighter);
			return;
		}

		BlockPos corePos = ArenaPositions.getCorePosition(arenaCenter, coreCountry);
		if (distanceSqrToBlock(fighter, corePos) > CORE_ATTACK_RANGE_SQR) {
			return;
		}

		if (state.windupTicksRemaining > 0) {
			return;
		}

		long now = level.getGameTime();
		if (now < state.nextAttackGameTime) {
			return;
		}

		float damage = (float) fighter.getAttributeValue(Attributes.ATTACK_DAMAGE);
		if (damage <= 0.0F) {
			return;
		}

		fighter.swing(InteractionHand.MAIN_HAND, true);
		state.windupTicksRemaining = CORE_WINDUP_TICKS;
		state.windupCoreTarget = coreCountry;
	}

	private void executeCoreHit(
			ServerLevel level,
			ArenaFighterEntity fighter,
			Country selfCountry,
			Country coreCountry,
			BlockPos arenaCenter,
			FighterCoreCombat state) {
		state.windupTicksRemaining = 0;
		state.windupCoreTarget = null;

		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return;
		}
		if (!fighter.isAlive() || !FighterFactory.isArenaFighter(fighter)) {
			return;
		}
		if (selfCountry == null || selfCountry == coreCountry) {
			return;
		}
		if (ArenaCoreManager.get().isCoreProtected(level, coreCountry)) {
			cancelCoreAttack(fighter);
			return;
		}

		ArenaCoreState coreState = ArenaCoreManager.get().getState(coreCountry);
		if (!coreState.isActive() || coreState.isDestroyed()) {
			cancelCoreAttack(fighter);
			return;
		}

		BlockPos corePos = ArenaPositions.getCorePosition(arenaCenter, coreCountry);
		if (distanceSqrToBlock(fighter, corePos) > CORE_ATTACK_RANGE_SQR) {
			return;
		}

		long now = level.getGameTime();
		if (now < state.nextAttackGameTime) {
			return;
		}

		float damage = (float) fighter.getAttributeValue(Attributes.ATTACK_DAMAGE);
		if (damage <= 0.0F) {
			return;
		}

		float before = coreState.getCurrentHealth();
		float after = ArenaCoreManager.get().damageFromFighter(
				level.getServer(), level, coreCountry, selfCountry, damage);
		state.nextAttackGameTime = now + CORE_ATTACK_COOLDOWN_TICKS;

		if (before <= after) {
			state.lastWaitReason = "core protected";
			return;
		}

		playAttackEffects(level, corePos);
		recordDefenseThreat(coreCountry, fighter.getUUID(), now);
		state.lastCoreDamageGameTime = now;

		if (before > 0.0F && after <= 0.0F) {
			clearCoreTargetsForCountry(coreCountry);
		}
	}

	private void cancelCoreAttack(ArenaFighterEntity fighter) {
		cancelCoreAttack(fighter, null);
	}

	private void cancelCoreAttack(ArenaFighterEntity fighter, String reason) {
		FighterCoreCombat state = fighters.get(fighter.getUUID());
		if (state != null && reason != null) {
			state.lastCancelReason = reason;
			state.lastWaitReason = reason;
		}
		clearCoreTarget(fighter.getUUID());
		stopCoreNavigation(fighter);
	}

	private void recordDefenseThreat(Country defendingCountry, UUID attackerId, long gameTime) {
		defenseThreats.put(defendingCountry, new DefenseThreat(attackerId, gameTime + DEFENSE_THREAT_TICKS));
	}

	private void ensureNavigatingToCore(
			ServerLevel level,
			ArenaFighterEntity fighter,
			BlockPos attackPos,
			BlockPos corePos,
			FighterCoreCombat state,
			boolean force) {
		double distToCoreSqr = distanceSqrToBlock(fighter, corePos);
		if (distToCoreSqr <= CORE_ATTACK_RANGE_SQR) {
			return;
		}

		BlockPos navTarget = attackPos;
		double distSqr = distanceSqrToBlock(fighter, attackPos);
		if (distSqr <= APPROACH_ARRIVAL_SQR && distToCoreSqr > CORE_ATTACK_RANGE_SQR) {
			navTarget = corePos;
			distSqr = distToCoreSqr;
		}

		if (distSqr <= APPROACH_ARRIVAL_SQR) {
			state.lastWaitReason = "out of core range";
			return;
		}

		PathNavigation navigation = fighter.getNavigation();
		boolean pathActive = navigation.isInProgress() && !navigation.isDone();
		boolean targetMoved = state.lastMoveTarget == null || !state.lastMoveTarget.equals(navTarget);
		boolean pathOff = false;

		if (pathActive && navigation.getTargetPos() != null) {
			double dx = navigation.getTargetPos().getX() + 0.5 - (navTarget.getX() + 0.5);
			double dz = navigation.getTargetPos().getZ() + 0.5 - (navTarget.getZ() + 0.5);
			pathOff = dx * dx + dz * dz >= REPATH_OFFSET_SQR;
		}

		boolean stoppedTooFar = !pathActive && distSqr > APPROACH_ARRIVAL_SQR;

		if (force || targetMoved || pathOff || stoppedTooFar) {
			boolean started = navigation.moveTo(
					navTarget.getX() + 0.5,
					navTarget.getY(),
					navTarget.getZ() + 0.5,
					CHASE_SPEED);
			if (!started) {
				state.lastWaitReason = "no path to approach";
			} else {
				state.lastWaitReason = null;
			}
			state.lastMoveTarget = navTarget.immutable();
		}
	}

	private static void playAttackEffects(ServerLevel level, BlockPos corePos) {
		ArenaCombatSpectacle.onCoreHit(level, corePos);
	}

	private static void stopCoreNavigation(ArenaFighterEntity fighter) {
		fighter.getNavigation().stop();
	}

	private static ArenaFighterEntity findFighter(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(id);
			if (entity instanceof ArenaFighterEntity fighter) {
				return fighter;
			}
		}
		return null;
	}

	private static double distanceSqrToBlock(Entity entity, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		double dx = entity.getX() - center.x;
		double dy = entity.getY() - center.y;
		double dz = entity.getZ() - center.z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static final class FighterCoreCombat {
		private Country coreTarget;
		/** True while marching to a (possibly protected) enemy front — no core damage. */
		private boolean rallyOnly;
		private Country windupCoreTarget;
		private int windupTicksRemaining;
		private long nextAttackGameTime;
		private BlockPos lastMoveTarget;
		private String lastCancelReason;
		private String lastWaitReason;
		private long lastCoreDamageGameTime;
	}

	private static final class DefenseThreat {
		private final UUID attackerId;
		private final long expireGameTime;

		private DefenseThreat(UUID attackerId, long expireGameTime) {
			this.attackerId = attackerId;
			this.expireGameTime = expireGameTime;
		}
	}
}
