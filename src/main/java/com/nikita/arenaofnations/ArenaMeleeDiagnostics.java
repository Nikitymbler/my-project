package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Round-level melee diagnostics with a rolling 10-second window.
 */
public final class ArenaMeleeDiagnostics {
	private static final int WINDOW_TICKS = 200;

	private static final MeleeCounters round = new MeleeCounters();
	private static final MeleeCounters window = new MeleeCounters();
	private static long windowStartGameTime = -1L;

	private ArenaMeleeDiagnostics() {
	}

	public static void reset() {
		round.clear();
		window.clear();
		windowStartGameTime = -1L;
	}

	public static void onSwing(long gameTime) {
		bump(c -> c.swings++, gameTime);
	}

	public static void onWindupCompleted(long gameTime) {
		bump(c -> c.windupsCompleted++, gameTime);
	}

	public static void onDoHurtTarget(long gameTime, boolean struck) {
		bump(c -> {
			c.doHurtTargetCalls++;
			if (!struck) {
				c.rejectedVanilla++;
			}
		}, gameTime);
	}

	public static void onDamagingHit(long gameTime) {
		bump(c -> c.damagingHits++, gameTime);
	}

	public static void onCanceledDead(long gameTime) {
		bump(c -> c.canceledDead++, gameTime);
	}

	public static void onCanceledRange(long gameTime) {
		bump(c -> c.canceledRange++, gameTime);
	}

	public static void onCanceledLos(long gameTime) {
		bump(c -> c.canceledLos++, gameTime);
	}

	public static void onCanceledTargetInvalid(long gameTime) {
		bump(c -> c.canceledTargetInvalid++, gameTime);
	}

	public static void onSwarmSuppressed(long gameTime) {
		bump(c -> c.swarmSuppressed++, gameTime);
	}

	public static void onTargetSwitch(long gameTime) {
		bump(c -> c.targetSwitches++, gameTime);
	}

	public static void onTargetAssignment(long gameTime) {
		bump(c -> c.targetAssignments++, gameTime);
	}

	public static void onStartCheck(long gameTime, ArenaFighterMeleeRange.StartGateResult result) {
		bump(c -> {
			c.startChecks++;
			switch (result) {
				case ALLOWED -> c.startAllowed++;
				case BLOCKED_RANGE -> c.startBlockedRange++;
				case BLOCKED_VERTICAL -> c.startBlockedVertical++;
				case BLOCKED_LOS -> c.startBlockedLos++;
			}
		}, gameTime);
	}

	public static void onGoalStart(long gameTime) {
		bump(c -> c.goalStarts++, gameTime);
	}

	public static void onGoalStop(long gameTime, boolean becauseNavigationDone) {
		bump(c -> {
			c.goalStops++;
			if (becauseNavigationDone) {
				c.goalStopsBecauseNavigationDone++;
			}
		}, gameTime);
	}

	public static void onRepathAttempt(long gameTime, boolean success) {
		bump(c -> {
			c.repathAttempts++;
			if (!success) {
				c.repathFailures++;
			}
		}, gameTime);
	}

	private static void bump(java.util.function.Consumer<MeleeCounters> increment, long gameTime) {
		advanceWindow(gameTime);
		increment.accept(round);
		increment.accept(window);
	}

	private static void advanceWindow(long gameTime) {
		if (windowStartGameTime < 0L || gameTime - windowStartGameTime >= WINDOW_TICKS) {
			window.clear();
			windowStartGameTime = gameTime;
		}
	}

	public static String buildStatusText(MinecraftServer server, ServerLevel level) {
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		long now = fightLevel.getGameTime();

		Map<FighterTier, TierAggregate> byTier = new EnumMap<>(FighterTier.class);
		Map<Country, CountryAggregate> byCountry = new EnumMap<>(Country.class);

		int alive = 0;
		int withValidTarget = 0;
		int withoutTarget = 0;
		int meleeGoalsRunning = 0;
		int navigationActive = 0;
		int navigationDone = 0;
		int insideStartRange = 0;
		int blockedByRange = 0;
		int blockedByVertical = 0;
		int blockedByLos = 0;
		int inWindup = 0;

		List<ProblemFighter> problems = new ArrayList<>();

		for (Entity entity : fightLevel.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !fighter.isAlive() || !FighterFactory.isArenaFighter(fighter)) {
				continue;
			}

			alive++;
			FighterTier tier = fighter.getArenaTier();
			Country country = fighter.getArenaCountry();
			ArenaFighterMeleeStats stats = fighter.getMeleeStats();

			if (tier != null) {
				byTier.computeIfAbsent(tier, ignored -> new TierAggregate()).addLiving(stats);
			}
			if (country != null) {
				byCountry.computeIfAbsent(country, ignored -> new CountryAggregate()).addLiving(stats);
			}

			LivingEntity target = fighter.getTarget();
			boolean validTarget = isValidEnemyTarget(fighter, target);
			if (validTarget) {
				withValidTarget++;
			} else {
				withoutTarget++;
			}

			if (fighter.isMeleeGoalRunning()) {
				meleeGoalsRunning++;
			}
			boolean navActive = fighter.getNavigation().isInProgress() && !fighter.getNavigation().isDone();
			boolean navDone = fighter.getNavigation().isDone();
			if (navActive) {
				navigationActive++;
			}
			if (navDone) {
				navigationDone++;
			}
			if (fighter.isMeleeWindupActive()) {
				inWindup++;
			}

			ArenaFighterMeleeRange.StartGateResult gate = validTarget
					? ArenaFighterMeleeRange.passesStartGate(fighter, target)
					: null;
			if (gate == ArenaFighterMeleeRange.StartGateResult.ALLOWED) {
				insideStartRange++;
			} else if (gate == ArenaFighterMeleeRange.StartGateResult.BLOCKED_RANGE) {
				blockedByRange++;
			} else if (gate == ArenaFighterMeleeRange.StartGateResult.BLOCKED_VERTICAL) {
				blockedByVertical++;
			} else if (gate == ArenaFighterMeleeRange.StartGateResult.BLOCKED_LOS) {
				blockedByLos++;
			}

			if (shouldListAsProblem(fighter, target, validTarget, navDone, gate, now)) {
				problems.add(new ProblemFighter(fighter, stats, target, validTarget, navDone, gate, now));
			}
		}

		StringBuilder builder = new StringBuilder("Melee diagnostics:\n");
		builder.append("живые=").append(alive)
				.append(", valid target=").append(withValidTarget)
				.append(", без цели=").append(withoutTarget)
				.append(", melee goal=").append(meleeGoalsRunning)
				.append(", nav active=").append(navigationActive)
				.append(", nav done=").append(navigationDone)
				.append(", start allowed=").append(insideStartRange)
				.append(", blocked range=").append(blockedByRange)
				.append(", blocked vertical=").append(blockedByVertical)
				.append(", blocked LoS=").append(blockedByLos)
				.append(", windup=").append(inWindup)
				.append('\n');

		appendCounterBlock(builder, "раунд", round);
		appendCounterBlock(builder, "последние 10с", window);

		builder.append("\nпо tier (живые + раунд):");
		for (FighterTier tier : FighterTier.values()) {
			TierAggregate agg = byTier.get(tier);
			if (agg == null || agg.living == 0) {
				continue;
			}
			builder.append('\n')
					.append("- ")
					.append(tier.getDisplayName())
					.append(": живые=")
					.append(agg.living)
					.append(", swings=")
					.append(agg.swings)
					.append(", hits=")
					.append(agg.damagingHits)
					.append(", startAllowed=")
					.append(agg.startAllowed)
					.append(", blockedRange=")
					.append(agg.startBlockedRange)
					.append(", switches=")
					.append(agg.targetSwitches);
		}

		builder.append("\nпо стране (живые + раунд):");
		for (Country country : Country.values()) {
			CountryAggregate agg = byCountry.get(country);
			if (agg == null || agg.living == 0) {
				continue;
			}
			builder.append('\n')
					.append("- ")
					.append(country.getDisplayName())
					.append(": живые=")
					.append(agg.living)
					.append(", swings=")
					.append(agg.swings)
					.append(", hits=")
					.append(agg.damagingHits)
					.append(", assignments=")
					.append(agg.targetAssignments)
					.append(", switches=")
					.append(agg.targetSwitches);
		}

		problems.sort(Comparator.comparingInt((ProblemFighter p) -> problemScore(p)).reversed());
		builder.append("\n\nпроблемные бойцы (до 10):");
		int listed = 0;
		for (ProblemFighter problem : problems) {
			if (listed >= 10) {
				break;
			}
			appendProblemLine(builder, listed + 1, problem, now);
			listed++;
		}
		if (listed == 0) {
			builder.append("\n(нет)");
		}

		return builder.toString();
	}

	private static boolean isValidEnemyTarget(ArenaFighterEntity fighter, LivingEntity target) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}
		if (!(target instanceof ArenaFighterEntity enemy) || !FighterFactory.isArenaFighter(enemy)) {
			return false;
		}
		Country self = fighter.getArenaCountry();
		Country other = enemy.getArenaCountry();
		return self != null && other != null && self != other;
	}

	private static boolean shouldListAsProblem(
			ArenaFighterEntity fighter,
			LivingEntity target,
			boolean validTarget,
			boolean navDone,
			ArenaFighterMeleeRange.StartGateResult gate,
			long now) {
		ArenaFighterMeleeStats stats = fighter.getMeleeStats();
		if (!validTarget) {
			return true;
		}
		if (!fighter.isMeleeGoalRunning()) {
			return true;
		}
		if (navDone && gate != ArenaFighterMeleeRange.StartGateResult.ALLOWED) {
			return true;
		}
		if (gate == ArenaFighterMeleeRange.StartGateResult.BLOCKED_LOS) {
			return true;
		}
		long assignedAt = fighter.getTargetAssignedGameTime();
		if (assignedAt >= 0L && now - assignedAt > 40L && stats.getSwings() == 0) {
			return true;
		}
		return stats.getSwings() > 0 && stats.getDamagingHits() == 0;
	}

	private static int problemScore(ProblemFighter problem) {
		if (!problem.validTarget) {
			return 1000;
		}
		if (!problem.fighter.isMeleeGoalRunning()) {
			return 900;
		}
		return problem.stats.getStartBlockedRange() + problem.stats.getStartBlockedLos();
	}

	private static void appendProblemLine(StringBuilder builder, int index, ProblemFighter problem, long now) {
		ArenaFighterEntity fighter = problem.fighter;
		LivingEntity target = problem.target;
		double centerDist = target == null ? -1.0 : ArenaFighterMeleeRange.horizontalDistance(fighter, target);
		double edgeDist = target == null ? -1.0 : ArenaFighterMeleeRange.horizontalEdgeDistance(fighter, target);
		long sinceAssign = fighter.getTargetAssignedGameTime() < 0L
				? -1L
				: now - fighter.getTargetAssignedGameTime();
		long sinceSwing = problem.stats.getLastSwingGameTime() < 0L
				? -1L
				: now - problem.stats.getLastSwingGameTime();

		builder.append('\n')
				.append(index)
				.append(". ")
				.append(formatCountryTier(fighter))
				.append(" -> ")
				.append(target == null ? "none" : formatCountryTier(target))
				.append(", centerXZ=")
				.append(centerDist < 0.0 ? "?" : String.format(Locale.US, "%.2f", centerDist))
				.append(", edge=")
				.append(edgeDist < 0.0 ? "?" : String.format(Locale.US, "%.2f", edgeDist))
				.append(", startReach=")
				.append(ArenaFighterMeleeRange.START_REACH)
				.append(", vertical=")
				.append(target == null ? "?" : String.format(Locale.US, "%.2f",
						ArenaFighterMeleeRange.verticalDifference(fighter, target)))
				.append(", LoS=")
				.append(target == null ? "?" : ArenaFighterMeleeRange.hasMeleeLineOfSight(fighter, target))
				.append(", goal=")
				.append(fighter.isMeleeGoalRunning())
				.append(", navDone=")
				.append(problem.navDone)
				.append(", gate=")
				.append(problem.gate == null ? "n/a" : problem.gate.name())
				.append(", wait=")
				.append(problem.stats.getLastStartGateReason())
				.append(", sinceAssign=")
				.append(sinceAssign < 0L ? "?" : sinceAssign + "t")
				.append(", sinceSwing=")
				.append(sinceSwing < 0L ? "never" : sinceSwing + "t");
	}

	private static String formatCountryTier(ArenaFighterEntity fighter) {
		return (fighter.getArenaCountry() == null ? "?" : fighter.getArenaCountry().getDisplayName())
				+ '/'
				+ (fighter.getArenaTier() == null ? "?" : fighter.getArenaTier().getDisplayName());
	}

	private static String formatCountryTier(LivingEntity target) {
		if (!(target instanceof ArenaFighterEntity fighter)) {
			return "?";
		}
		return formatCountryTier(fighter);
	}

	private static void appendCounterBlock(StringBuilder builder, String label, MeleeCounters counters) {
		builder.append('\n')
				.append(label)
				.append(": swings=")
				.append(counters.swings)
				.append(", windups=")
				.append(counters.windupsCompleted)
				.append(", doHurtTarget=")
				.append(counters.doHurtTargetCalls)
				.append(", damagingHits=")
				.append(counters.damagingHits)
				.append(", startChecks=")
				.append(counters.startChecks)
				.append(", startAllowed=")
				.append(counters.startAllowed)
				.append(", blockedRange=")
				.append(counters.startBlockedRange)
				.append(", blockedVertical=")
				.append(counters.startBlockedVertical)
				.append(", blockedLoS=")
				.append(counters.startBlockedLos)
				.append(", cancelDead=")
				.append(counters.canceledDead)
				.append(", cancelRange=")
				.append(counters.canceledRange)
				.append(", cancelLoS=")
				.append(counters.canceledLos)
				.append(", rejectVanilla=")
				.append(counters.rejectedVanilla)
				.append(", swarm=")
				.append(counters.swarmSuppressed)
				.append(", assignments=")
				.append(counters.targetAssignments)
				.append(", switches=")
				.append(counters.targetSwitches)
				.append(", goalStarts=")
				.append(counters.goalStarts)
				.append(", goalStops=")
				.append(counters.goalStops)
				.append(", goalStopsNavDone=")
				.append(counters.goalStopsBecauseNavigationDone)
				.append(", repath=")
				.append(counters.repathAttempts)
				.append('/')
				.append(counters.repathFailures);
	}

	private static final class MeleeCounters {
		private int swings;
		private int windupsCompleted;
		private int doHurtTargetCalls;
		private int damagingHits;
		private int canceledDead;
		private int canceledRange;
		private int canceledLos;
		private int canceledTargetInvalid;
		private int rejectedVanilla;
		private int swarmSuppressed;
		private int targetSwitches;
		private int targetAssignments;
		private int startChecks;
		private int startAllowed;
		private int startBlockedRange;
		private int startBlockedVertical;
		private int startBlockedLos;
		private int goalStarts;
		private int goalStops;
		private int goalStopsBecauseNavigationDone;
		private int repathAttempts;
		private int repathFailures;

		private void clear() {
			swings = 0;
			windupsCompleted = 0;
			doHurtTargetCalls = 0;
			damagingHits = 0;
			canceledDead = 0;
			canceledRange = 0;
			canceledLos = 0;
			canceledTargetInvalid = 0;
			rejectedVanilla = 0;
			swarmSuppressed = 0;
			targetSwitches = 0;
			targetAssignments = 0;
			startChecks = 0;
			startAllowed = 0;
			startBlockedRange = 0;
			startBlockedVertical = 0;
			startBlockedLos = 0;
			goalStarts = 0;
			goalStops = 0;
			goalStopsBecauseNavigationDone = 0;
			repathAttempts = 0;
			repathFailures = 0;
		}
	}

	private static final class TierAggregate {
		private int living;
		private int swings;
		private int damagingHits;
		private int startAllowed;
		private int startBlockedRange;
		private int targetSwitches;

		private void addLiving(ArenaFighterMeleeStats stats) {
			living++;
			swings += stats.getSwings();
			damagingHits += stats.getDamagingHits();
			startAllowed += stats.getStartAllowed();
			startBlockedRange += stats.getStartBlockedRange();
			targetSwitches += stats.getTargetSwitches();
		}
	}

	private static final class CountryAggregate {
		private int living;
		private int swings;
		private int damagingHits;
		private int targetAssignments;
		private int targetSwitches;

		private void addLiving(ArenaFighterMeleeStats stats) {
			living++;
			swings += stats.getSwings();
			damagingHits += stats.getDamagingHits();
			targetAssignments += stats.getTargetAssignments();
			targetSwitches += stats.getTargetSwitches();
		}
	}

	private record ProblemFighter(
			ArenaFighterEntity fighter,
			ArenaFighterMeleeStats stats,
			LivingEntity target,
			boolean validTarget,
			boolean navDone,
			ArenaFighterMeleeRange.StartGateResult gate,
			long now) {
	}
}
