package com.nikita.arenaofnations;

/**
 * In-memory melee diagnostics for one fighter (not saved to NBT).
 */
public final class ArenaFighterMeleeStats {
	private int swings;
	private int windupsCompleted;
	private int doHurtTargetCalls;
	private int damagingHits;
	private int canceledDead;
	private int canceledRange;
	private int canceledLos;
	private int canceledTargetInvalid;
	private int rejectedVanilla;
	private int swarmSuppressedObserved;
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
	private String lastCancelReason = "none";
	private String lastStartGateReason = "none";
	private long lastDamagingHitGameTime = -1L;
	private long lastSwingGameTime = -1L;

	public void reset() {
		swings = 0;
		windupsCompleted = 0;
		doHurtTargetCalls = 0;
		damagingHits = 0;
		canceledDead = 0;
		canceledRange = 0;
		canceledLos = 0;
		canceledTargetInvalid = 0;
		rejectedVanilla = 0;
		swarmSuppressedObserved = 0;
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
		lastCancelReason = "none";
		lastStartGateReason = "none";
		lastDamagingHitGameTime = -1L;
		lastSwingGameTime = -1L;
	}

	public void recordSwing(long gameTime) {
		swings++;
		lastSwingGameTime = gameTime;
	}

	public void recordWindupCompleted() {
		windupsCompleted++;
	}

	public void recordDoHurtTargetCall(boolean struck) {
		doHurtTargetCalls++;
		if (!struck) {
			rejectedVanilla++;
		}
	}

	public void recordDamagingHit(long gameTime) {
		damagingHits++;
		lastDamagingHitGameTime = gameTime;
	}

	public void recordCanceledDead() {
		canceledDead++;
		lastCancelReason = "target_dead";
	}

	public void recordCanceledRange() {
		canceledRange++;
		lastCancelReason = "out_of_range";
	}

	public void recordCanceledLos() {
		canceledLos++;
		lastCancelReason = "no_los";
	}

	public void recordCanceledTargetInvalid() {
		canceledTargetInvalid++;
		lastCancelReason = "target_invalid";
	}

	public void recordSwarmSuppressed() {
		swarmSuppressedObserved++;
	}

	public void recordTargetSwitch() {
		targetSwitches++;
	}

	public void recordTargetAssignment() {
		targetAssignments++;
	}

	public void recordStartCheck(ArenaFighterMeleeRange.StartGateResult result) {
		startChecks++;
		switch (result) {
			case ALLOWED -> {
				startAllowed++;
				lastStartGateReason = "allowed";
			}
			case BLOCKED_RANGE -> {
				startBlockedRange++;
				lastStartGateReason = "blocked_range";
			}
			case BLOCKED_VERTICAL -> {
				startBlockedVertical++;
				lastStartGateReason = "blocked_vertical";
			}
			case BLOCKED_LOS -> {
				startBlockedLos++;
				lastStartGateReason = "blocked_los";
			}
		}
	}

	public void recordGoalStart() {
		goalStarts++;
	}

	public void recordGoalStop(boolean becauseNavigationDone) {
		goalStops++;
		if (becauseNavigationDone) {
			goalStopsBecauseNavigationDone++;
		}
	}

	public void recordRepathAttempt(boolean success) {
		repathAttempts++;
		if (!success) {
			repathFailures++;
		}
	}

	public int getSwings() {
		return swings;
	}

	public int getWindupsCompleted() {
		return windupsCompleted;
	}

	public int getDoHurtTargetCalls() {
		return doHurtTargetCalls;
	}

	public int getDamagingHits() {
		return damagingHits;
	}

	public int getCanceledDead() {
		return canceledDead;
	}

	public int getCanceledRange() {
		return canceledRange;
	}

	public int getCanceledLos() {
		return canceledLos;
	}

	public int getCanceledTargetInvalid() {
		return canceledTargetInvalid;
	}

	public int getRejectedVanilla() {
		return rejectedVanilla;
	}

	public int getSwarmSuppressedObserved() {
		return swarmSuppressedObserved;
	}

	public int getTargetSwitches() {
		return targetSwitches;
	}

	public int getTargetAssignments() {
		return targetAssignments;
	}

	public int getStartChecks() {
		return startChecks;
	}

	public int getStartAllowed() {
		return startAllowed;
	}

	public int getStartBlockedRange() {
		return startBlockedRange;
	}

	public int getStartBlockedVertical() {
		return startBlockedVertical;
	}

	public int getStartBlockedLos() {
		return startBlockedLos;
	}

	public int getGoalStarts() {
		return goalStarts;
	}

	public int getGoalStops() {
		return goalStops;
	}

	public int getGoalStopsBecauseNavigationDone() {
		return goalStopsBecauseNavigationDone;
	}

	public int getRepathAttempts() {
		return repathAttempts;
	}

	public int getRepathFailures() {
		return repathFailures;
	}

	public String getLastCancelReason() {
		return lastCancelReason;
	}

	public String getLastStartGateReason() {
		return lastStartGateReason;
	}

	public long getLastDamagingHitGameTime() {
		return lastDamagingHitGameTime;
	}

	public long getLastSwingGameTime() {
		return lastSwingGameTime;
	}
}
