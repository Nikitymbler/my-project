package com.nikita.arenaofnations;

/**
 * Pure core HP regeneration rules (no Minecraft types).
 */
public final class ArenaCoreRegenMath {
	public static final float CORE_REGEN_AMOUNT = 5.0F;
	public static final int CORE_REGEN_INTERVAL_TICKS = 100;
	public static final int CORE_REGEN_DAMAGE_DELAY_TICKS = 300;

	public enum BlockedReason {
		NONE,
		NOT_BATTLE,
		NOT_ACTIVE,
		ELIMINATED,
		CORE_MISSING,
		CORE_DESTROYED,
		FULL_HP,
		DAMAGE_COOLDOWN,
		WAITING_INTERVAL
	}

	public record Eval(
			boolean eligible,
			BlockedReason reason,
			long damageDelayRemainingTicks,
			float healAmount) {
	}

	private ArenaCoreRegenMath() {
	}

	public static long damageDelayRemainingTicks(long gameTime, long lastCoreDamageGameTime) {
		if (lastCoreDamageGameTime < 0L) {
			return 0L;
		}
		long readyAt = lastCoreDamageGameTime + CORE_REGEN_DAMAGE_DELAY_TICKS;
		return Math.max(0L, readyAt - gameTime);
	}

	public static boolean damageDelayElapsed(long gameTime, long lastCoreDamageGameTime) {
		return damageDelayRemainingTicks(gameTime, lastCoreDamageGameTime) <= 0L;
	}

	public static boolean regenIntervalElapsed(long gameTime, long lastCoreRegenGameTime) {
		if (lastCoreRegenGameTime < 0L) {
			return true;
		}
		return gameTime >= lastCoreRegenGameTime + CORE_REGEN_INTERVAL_TICKS;
	}

	/**
	 * Single regen portion capped to max HP. Never heals a destroyed (HP &lt;= 0) core.
	 */
	public static float computeHealAmount(float currentHp, float maxHp) {
		if (currentHp <= 0.0F || maxHp <= 0.0F || currentHp >= maxHp) {
			return 0.0F;
		}
		return Math.min(CORE_REGEN_AMOUNT, maxHp - currentHp);
	}

	public static float applyHealCap(float currentHp, float maxHp, float amount) {
		if (amount <= 0.0F || currentHp <= 0.0F) {
			return currentHp;
		}
		return Math.min(maxHp, currentHp + amount);
	}

	public static Eval evaluate(
			boolean battlePhase,
			boolean activeParticipant,
			boolean eliminated,
			boolean corePresent,
			boolean coreDestroyedOrZeroHp,
			float currentHp,
			float maxHp,
			long gameTime,
			long lastCoreDamageGameTime,
			long lastCoreRegenGameTime) {
		long delayRemaining = damageDelayRemainingTicks(gameTime, lastCoreDamageGameTime);

		if (!battlePhase) {
			return new Eval(false, BlockedReason.NOT_BATTLE, delayRemaining, 0.0F);
		}
		if (!activeParticipant) {
			return new Eval(false, BlockedReason.NOT_ACTIVE, delayRemaining, 0.0F);
		}
		if (eliminated) {
			return new Eval(false, BlockedReason.ELIMINATED, delayRemaining, 0.0F);
		}
		if (!corePresent) {
			return new Eval(false, BlockedReason.CORE_MISSING, delayRemaining, 0.0F);
		}
		if (coreDestroyedOrZeroHp || currentHp <= 0.0F) {
			return new Eval(false, BlockedReason.CORE_DESTROYED, delayRemaining, 0.0F);
		}
		if (currentHp >= maxHp) {
			return new Eval(false, BlockedReason.FULL_HP, delayRemaining, 0.0F);
		}
		if (delayRemaining > 0L) {
			return new Eval(false, BlockedReason.DAMAGE_COOLDOWN, delayRemaining, 0.0F);
		}
		if (!regenIntervalElapsed(gameTime, lastCoreRegenGameTime)) {
			return new Eval(false, BlockedReason.WAITING_INTERVAL, delayRemaining, 0.0F);
		}

		float heal = computeHealAmount(currentHp, maxHp);
		if (heal <= 0.0F) {
			return new Eval(false, BlockedReason.FULL_HP, delayRemaining, 0.0F);
		}
		return new Eval(true, BlockedReason.NONE, delayRemaining, heal);
	}
}
