package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArenaCoreRegenTest {
	private static final float MAX = 100.0F;

	@Test
	void constantsMatchSpec() {
		assertEquals(5.0F, ArenaCoreRegenMath.CORE_REGEN_AMOUNT);
		assertEquals(100, ArenaCoreRegenMath.CORE_REGEN_INTERVAL_TICKS);
		assertEquals(300, ArenaCoreRegenMath.CORE_REGEN_DAMAGE_DELAY_TICKS);
	}

	@Test
	void battleAfterDelayAndIntervalHealsFive() {
		long damageAt = 1000L;
		long firstRegenAt = damageAt + 300L;
		ArenaCoreRegenMath.Eval eval = eligible(90.0F, firstRegenAt, damageAt, -1L);
		assertTrue(eval.eligible());
		assertEquals(5.0F, eval.healAmount());
		assertEquals(95.0F, ArenaCoreRegenMath.applyHealCap(90.0F, MAX, eval.healAmount()));
	}

	@Test
	void fourteenSecondsAfterDamageNoRegen() {
		long damageAt = 1000L;
		ArenaCoreRegenMath.Eval eval = eligible(90.0F, damageAt + 280L, damageAt, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.DAMAGE_COOLDOWN, eval.reason());
		assertEquals(20L, eval.damageDelayRemainingTicks());
	}

	@Test
	void fifteenSecondsAfterDamageAllowsRegen() {
		long damageAt = 1000L;
		ArenaCoreRegenMath.Eval eval = eligible(90.0F, damageAt + 300L, damageAt, -1L);
		assertTrue(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.NONE, eval.reason());
	}

	@Test
	void newDamageAtTenSecondsRestartsDelay() {
		long firstDamage = 1000L;
		long secondDamage = 1000L + 200L; // +10s
		// At former ready time (1300) still blocked after restart.
		ArenaCoreRegenMath.Eval atOldReady = eligible(90.0F, firstDamage + 300L, secondDamage, -1L);
		assertFalse(atOldReady.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.DAMAGE_COOLDOWN, atOldReady.reason());
		// Ready only at secondDamage + 300 = 1500.
		ArenaCoreRegenMath.Eval atNewReady = eligible(90.0F, secondDamage + 300L, secondDamage, -1L);
		assertTrue(atNewReady.eligible());
	}

	@Test
	void hp98CapsTo100Not103() {
		assertEquals(100.0F, ArenaCoreRegenMath.applyHealCap(98.0F, MAX, 5.0F));
		ArenaCoreRegenMath.Eval eval = eligible(98.0F, 2000L, 1000L, -1L);
		assertTrue(eval.eligible());
		assertEquals(2.0F, eval.healAmount());
	}

	@Test
	void fullHpNoRegen() {
		ArenaCoreRegenMath.Eval eval = eligible(100.0F, 2000L, 1000L, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.FULL_HP, eval.reason());
	}

	@Test
	void waitingPhaseNoRegen() {
		ArenaCoreRegenMath.Eval eval = ArenaCoreRegenMath.evaluate(
				false, true, false, true, false, 50.0F, MAX, 2000L, 1000L, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.NOT_BATTLE, eval.reason());
	}

	@Test
	void breakPhaseNoRegen() {
		// BREAK is not BATTLE — same gate.
		ArenaCoreRegenMath.Eval eval = ArenaCoreRegenMath.evaluate(
				false, true, false, true, false, 50.0F, MAX, 5000L, 1000L, 1300L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.NOT_BATTLE, eval.reason());
	}

	@Test
	void eliminatedNoRegen() {
		ArenaCoreRegenMath.Eval eval = ArenaCoreRegenMath.evaluate(
				true, true, true, true, false, 50.0F, MAX, 2000L, 1000L, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.ELIMINATED, eval.reason());
	}

	@Test
	void zeroHpDoesNotRevive() {
		ArenaCoreRegenMath.Eval eval = ArenaCoreRegenMath.evaluate(
				true, true, false, true, true, 0.0F, MAX, 2000L, 1000L, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.CORE_DESTROYED, eval.reason());
		assertEquals(0.0F, ArenaCoreRegenMath.computeHealAmount(0.0F, MAX));

		ArenaCoreState state = new ArenaCoreState(Country.RU, MAX);
		state.activate(MAX);
		state.setCurrentHealthForTest(0.0F);
		assertEquals(0.0F, state.regenerate(5.0F));
		assertTrue(state.isDestroyed());
	}

	@Test
	void inactiveCountryNoRegen() {
		ArenaCoreRegenMath.Eval eval = ArenaCoreRegenMath.evaluate(
				true, false, false, true, false, 50.0F, MAX, 2000L, 1000L, -1L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.NOT_ACTIVE, eval.reason());
	}

	@Test
	void twoCountriesIndependentTimestamps() {
		long t = 2000L;
		ArenaCoreRegenMath.Eval ru = ArenaCoreRegenMath.evaluate(
				true, true, false, true, false, 80.0F, MAX, t, 1000L, -1L);
		ArenaCoreRegenMath.Eval ua = ArenaCoreRegenMath.evaluate(
				true, true, false, true, false, 80.0F, MAX, t, 1900L, -1L);
		assertTrue(ru.eligible());
		assertFalse(ua.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.DAMAGE_COOLDOWN, ua.reason());
	}

	@Test
	void damageBetweenRegenPortionsRestartsDelay() {
		long firstDamage = 1000L;
		long firstRegen = 1300L;
		long midDamage = 1350L;
		// Would have been next regen at 1400, but damage at 1350 blocks until 1650.
		ArenaCoreRegenMath.Eval blocked = eligible(85.0F, 1400L, midDamage, firstRegen);
		assertFalse(blocked.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.DAMAGE_COOLDOWN, blocked.reason());
		ArenaCoreRegenMath.Eval ready = eligible(85.0F, midDamage + 300L, midDamage, firstRegen);
		assertTrue(ready.eligible());
	}

	@Test
	void afterLongBreakNoCatchUpHeals() {
		float hp = 50.0F;
		long lastDamage = 1000L;
		long lastRegen = 1300L;
		// Simulate returning to BATTLE after a long BREAK gap — only one portion.
		long now = 1300L + 10_000L;
		ArenaCoreRegenMath.Eval eval = eligible(hp, now, lastDamage, lastRegen);
		assertTrue(eval.eligible());
		assertEquals(5.0F, eval.healAmount());
		float afterOne = ArenaCoreRegenMath.applyHealCap(hp, MAX, eval.healAmount());
		assertEquals(55.0F, afterOne);
		// Same tick / same lastRegen update would block second portion.
		ArenaCoreRegenMath.Eval second = eligible(afterOne, now, lastDamage, now);
		assertFalse(second.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.WAITING_INTERVAL, second.reason());
	}

	@Test
	void resetClearsTimestamps() {
		ArenaCoreState state = new ArenaCoreState(Country.UA, MAX);
		state.activate(MAX);
		state.noteActualDamage(5000L);
		state.noteRegen(5300L);
		assertEquals(5000L, state.getLastCoreDamageGameTime());
		assertEquals(5300L, state.getLastCoreRegenGameTime());

		state.resetInactive(MAX);
		assertEquals(-1L, state.getLastCoreDamageGameTime());
		assertEquals(-1L, state.getLastCoreRegenGameTime());
	}

	@Test
	void waitingIntervalBlocksFasterThan100Ticks() {
		ArenaCoreRegenMath.Eval eval = eligible(80.0F, 1399L, 1000L, 1300L);
		assertFalse(eval.eligible());
		assertEquals(ArenaCoreRegenMath.BlockedReason.WAITING_INTERVAL, eval.reason());
		ArenaCoreRegenMath.Eval ready = eligible(80.0F, 1400L, 1000L, 1300L);
		assertTrue(ready.eligible());
	}

	@Test
	void stateRegenerateCapsAndIgnoresZeroAmount() {
		ArenaCoreState state = new ArenaCoreState(Country.KZ, MAX);
		state.activate(MAX);
		state.setCurrentHealthForTest(97.0F);
		assertEquals(100.0F, state.regenerate(5.0F));
		assertEquals(100.0F, state.regenerate(5.0F));
	}

	@Test
	void noteActualDamageOnlyViaPositiveLossSemantics() {
		ArenaCoreState state = new ArenaCoreState(Country.BY, MAX);
		state.activate(MAX);
		float before = state.getCurrentHealth();
		float after = state.damage(0.0F);
		assertEquals(before, after);
		assertEquals(-1L, state.getLastCoreDamageGameTime());
		state.damage(10.0F);
		// damage() alone does not set timestamp — manager does after actual loss.
		assertEquals(-1L, state.getLastCoreDamageGameTime());
		state.noteActualDamage(777L);
		assertEquals(777L, state.getLastCoreDamageGameTime());
	}

	@Test
	void matchManagerCallsRegenOnlyFromBattleTick() throws Exception {
		String source = java.nio.file.Files.readString(
				java.nio.file.Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchManager.java"),
				java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(source.contains("tickCoreRegen"));
		int waiting = source.indexOf("private void tickWaiting");
		int battle = source.indexOf("private void tickBattle");
		int brk = source.indexOf("private void tickBreak");
		assertTrue(waiting >= 0 && battle > waiting && brk > battle);
		assertFalse(source.substring(waiting, battle).contains("tickCoreRegen"));
		assertTrue(source.substring(battle, brk).contains("tickCoreRegen"));
		assertFalse(source.substring(brk).contains("tickCoreRegen"));
	}

	private static ArenaCoreRegenMath.Eval eligible(
			float hp,
			long gameTime,
			long lastDamage,
			long lastRegen) {
		return ArenaCoreRegenMath.evaluate(
				true,
				true,
				false,
				true,
				false,
				hp,
				MAX,
				gameTime,
				lastDamage,
				lastRegen);
	}
}
