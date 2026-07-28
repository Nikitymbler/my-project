package com.nikita.arenaofnations;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Direct ground melee chase: move toward the living target, then vanilla swing + delayed damage.
 * Sole owner of living-target navigation for {@link ArenaFighterEntity}.
 *
 * <p>Does not use {@link Goal.Flag#MOVE} — GoalSelector stops MOVE-flagged goals when
 * navigation.isDone(), which prevented attacks after path completion.
 */
public class ArenaFighterMeleeAttackGoal extends Goal {
	/** Mid-arc of the default 6-tick vanilla swing. */
	private static final int WINDUP_TICKS = LivingEntity.SWING_DURATION / 2;
	private static final double CHASE_SPEED = 1.2;
	private static final int REPATH_INTERVAL_TICKS = 5;
	private static final int FAST_REPATH_TICKS = 2;
	private static final double REPATH_TARGET_MOVE_SQR = 1.0 * 1.0;
	private static final int RETRY_AFTER_DEAD_TICKS = 2;
	private static final int RETRY_AFTER_RANGE_TICKS = 4;

	private static final AtomicBoolean LOGGED_FIRST_ATTACK = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_FIRST_WINDUP = new AtomicBoolean(false);

	private enum Phase {
		IDLE,
		WINDUP,
		RECOVERY
	}

	private final ArenaFighterEntity mob;
	private final double speedModifier;

	private Phase phase = Phase.IDLE;
	private int windupTicksRemaining;
	private LivingEntity windupTarget;
	private long windupStartGameTime = -1L;
	private int ticksUntilNextAttack;
	private int recoveryTicksRemaining;
	private int ticksUntilNextPath;
	private double lastPathTargetX;
	private double lastPathTargetZ;
	private boolean navigationWasDoneOnStop;

	public ArenaFighterMeleeAttackGoal(ArenaFighterEntity mob, double speedModifier, boolean followEvenIfNotSeen) {
		this.mob = mob;
		this.speedModifier = speedModifier > 0.0 ? speedModifier : CHASE_SPEED;
		this.setFlags(EnumSet.of(Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return isUsableTarget(this.mob.getTarget());
	}

	@Override
	public boolean canContinueToUse() {
		if (phase == Phase.WINDUP) {
			return windupTarget != null && windupTarget.isAlive() && !windupTarget.isRemoved();
		}
		return isUsableTarget(this.mob.getTarget());
	}

	@Override
	public void start() {
		this.ticksUntilNextAttack = 0;
		this.ticksUntilNextPath = 0;
		this.mob.setMeleeGoalRunning(true);
		this.mob.getMeleeStats().recordGoalStart();
		ArenaMeleeDiagnostics.onGoalStart(this.mob.level().getGameTime());
	}

	@Override
	public void stop() {
		navigationWasDoneOnStop = this.mob.getNavigation().isDone();
		this.mob.getMeleeStats().recordGoalStop(navigationWasDoneOnStop);
		ArenaMeleeDiagnostics.onGoalStop(this.mob.level().getGameTime(), navigationWasDoneOnStop);
		clearWindup();
		this.recoveryTicksRemaining = 0;
		this.mob.getNavigation().stop();
		this.mob.setMeleeGoalRunning(false);
	}

	@Override
	public void tick() {
		LivingEntity target = phase == Phase.WINDUP && windupTarget != null ? windupTarget : this.mob.getTarget();
		if (phase != Phase.WINDUP && !isUsableTarget(target)) {
			clearWindup();
			return;
		}

		suppressUpwardLunge();
		if (target != null) {
			this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		}

		if (phase == Phase.WINDUP) {
			tickWindup();
			return;
		}

		if (phase == Phase.RECOVERY) {
			this.mob.getNavigation().stop();
			recoveryTicksRemaining--;
			if (recoveryTicksRemaining <= 0) {
				phase = Phase.IDLE;
			}
			return;
		}

		if (target == null) {
			return;
		}

		if (ticksUntilNextAttack > 0) {
			ticksUntilNextAttack--;
		}

		ArenaFighterMeleeRange.StartGateResult gate = ArenaFighterMeleeRange.passesStartGate(this.mob, target);
		recordStartGate(gate);

		if (gate == ArenaFighterMeleeRange.StartGateResult.ALLOWED) {
			if (ticksUntilNextAttack <= 0) {
				this.mob.getNavigation().stop();
				beginWindup(target);
			}
			return;
		}

		ensureChasing(target, gate);
	}

	private void recordStartGate(ArenaFighterMeleeRange.StartGateResult gate) {
		long gameTime = this.mob.level().getGameTime();
		this.mob.getMeleeStats().recordStartCheck(gate);
		ArenaMeleeDiagnostics.onStartCheck(gameTime, gate);
	}

	private void ensureChasing(LivingEntity target, ArenaFighterMeleeRange.StartGateResult gate) {
		PathNavigation navigation = this.mob.getNavigation();
		boolean pathActive = navigation.isInProgress() && !navigation.isDone();
		boolean navigationDone = navigation.isDone();
		boolean targetMoved = horizontalDistSqr(lastPathTargetX, lastPathTargetZ, target.getX(), target.getZ())
				>= REPATH_TARGET_MOVE_SQR;

		if (ticksUntilNextPath > 0) {
			ticksUntilNextPath--;
		}

		int repathDelay = navigationDone ? FAST_REPATH_TICKS : REPATH_INTERVAL_TICKS;
		boolean shouldRepath = !pathActive || ticksUntilNextPath <= 0 || targetMoved || navigationDone;

		if (shouldRepath) {
			boolean success = navigation.moveTo(target, this.speedModifier);
			this.mob.getMeleeStats().recordRepathAttempt(success);
			ArenaMeleeDiagnostics.onRepathAttempt(this.mob.level().getGameTime(), success);
			lastPathTargetX = target.getX();
			lastPathTargetZ = target.getZ();
			ticksUntilNextPath = repathDelay;
		}
	}

	private void tickWindup() {
		LivingEntity target = windupTarget;
		long gameTime = this.mob.level().getGameTime();
		ArenaFighterMeleeStats stats = this.mob.getMeleeStats();

		this.mob.getNavigation().stop();
		suppressUpwardLunge();

		if (target == null || !target.isAlive() || target.isRemoved()) {
			stats.recordCanceledDead();
			ArenaMeleeDiagnostics.onCanceledDead(gameTime);
			clearWindup();
			ticksUntilNextAttack = RETRY_AFTER_DEAD_TICKS;
			return;
		}

		if (!isUsableTarget(target)) {
			stats.recordCanceledTargetInvalid();
			ArenaMeleeDiagnostics.onCanceledTargetInvalid(gameTime);
			clearWindup();
			return;
		}

		if (!ArenaFighterMeleeRange.isWithinConfirmationRange(this.mob, target, 0.0D)) {
			stats.recordCanceledRange();
			ArenaMeleeDiagnostics.onCanceledRange(gameTime);
			clearWindup();
			ticksUntilNextAttack = RETRY_AFTER_RANGE_TICKS;
			return;
		}

		if (!ArenaFighterMeleeRange.hasMeleeLineOfSight(this.mob, target)) {
			stats.recordCanceledLos();
			ArenaMeleeDiagnostics.onCanceledLos(gameTime);
			clearWindup();
			ticksUntilNextAttack = RETRY_AFTER_RANGE_TICKS;
			return;
		}

		windupTicksRemaining--;
		if (windupTicksRemaining > 0) {
			return;
		}

		stats.recordWindupCompleted();
		ArenaMeleeDiagnostics.onWindupCompleted(gameTime);

		boolean struck = this.mob.doHurtTarget(target);
		stats.recordDoHurtTargetCall(struck);
		ArenaMeleeDiagnostics.onDoHurtTarget(gameTime, struck);
		if (struck && this.mob.level() instanceof ServerLevel serverLevel) {
			ArenaCombatSpectacle.onMeleeHit(serverLevel, this.mob, target);
		}

		if (LOGGED_FIRST_ATTACK.compareAndSet(false, true)) {
			long delay = windupStartGameTime >= 0L ? gameTime - windupStartGameTime : -1L;
			ArenaOfNations.LOGGER.info(
					"ArenaFighterMeleeAttackGoal: first doHurtTarget - swingStart={}, hitTick={}, delayTicks={}, "
							+ "swingDuration={}, struck={}",
					windupStartGameTime,
					gameTime,
					delay,
					LivingEntity.SWING_DURATION,
					struck);
		}

		ticksUntilNextAttack = this.mob.getArenaTier().getAttackCooldownTicks();
		phase = Phase.RECOVERY;
		recoveryTicksRemaining = 2;
		windupTicksRemaining = 0;
		windupTarget = null;
		windupStartGameTime = -1L;
		this.mob.setMeleeWindupActive(false);
	}

	private void beginWindup(LivingEntity target) {
		long gameTime = this.mob.level().getGameTime();
		phase = Phase.WINDUP;
		windupTicksRemaining = WINDUP_TICKS;
		windupTarget = target;
		windupStartGameTime = gameTime;
		this.mob.setMeleeWindupActive(true);

		this.mob.getNavigation().stop();
		suppressUpwardLunge();
		this.mob.swing(InteractionHand.MAIN_HAND, true);
		if (this.mob.level() instanceof ServerLevel serverLevel) {
			ArenaCombatSpectacle.onMeleeSwing(serverLevel, this.mob);
		}

		ArenaFighterMeleeStats stats = this.mob.getMeleeStats();
		stats.recordSwing(gameTime);
		ArenaMeleeDiagnostics.onSwing(gameTime);

		if (LOGGED_FIRST_WINDUP.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info(
					"ArenaFighterMeleeAttackGoal: first swing - gameTime={}, damage in {} ticks, startReach={}",
					windupStartGameTime,
					WINDUP_TICKS,
					ArenaFighterMeleeRange.START_REACH);
		}
	}

	private boolean isUsableTarget(LivingEntity target) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}
		if (!(target instanceof ArenaFighterEntity defender)) {
			return false;
		}
		Country self = this.mob.getArenaCountry();
		Country other = defender.getArenaCountry();
		if (self == null || other == null || self == other) {
			return false;
		}
		if (ArenaCoreRescueManager.get().isEliminated(other)) {
			return false;
		}
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return false;
		}
		return true;
	}

	private void suppressUpwardLunge() {
		Vec3 motion = this.mob.getDeltaMovement();
		if (motion.y > 0.0D) {
			this.mob.setDeltaMovement(motion.x, 0.0D, motion.z);
		}
	}

	private void clearWindup() {
		phase = Phase.IDLE;
		windupTicksRemaining = 0;
		windupTarget = null;
		windupStartGameTime = -1L;
		this.mob.setMeleeWindupActive(false);
	}

	private static double horizontalDistSqr(double ax, double az, double bx, double bz) {
		double dx = bx - ax;
		double dz = bz - az;
		return dx * dx + dz * dz;
	}
}
