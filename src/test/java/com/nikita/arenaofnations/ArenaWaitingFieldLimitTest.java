package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ArenaWaitingFieldLimitTest {
	@Test
	void waitingFieldLimitConstantIs25() {
		assertEquals(25, ArenaReserveReleaseMath.WAITING_FIELD_LIMIT);
	}

	@Test
	void batch10Reserve60Releases10Plus10Plus5() {
		int[] waves = ArenaReserveReleaseMath.simulateWaitingWaves(10, 60, 0, 6);
		assertEquals(10, waves[0]);
		assertEquals(10, waves[1]);
		assertEquals(5, waves[2]);
		assertEquals(0, waves[3]);

		int field = waves[0] + waves[1] + waves[2];
		assertEquals(25, field);
		assertEquals(35, 60 - field);
	}

	@Test
	void batch100Reserve60Releases25InOneWave() {
		int[] waves = ArenaReserveReleaseMath.simulateWaitingWaves(100, 60, 0, 3);
		assertEquals(25, waves[0]);
		assertEquals(0, waves[1]);
		assertEquals(35, 60 - waves[0]);
	}

	@Test
	void active23Batch10ReleasesOnly2() {
		assertEquals(
				2,
				ArenaReserveReleaseMath.computeActualRelease(
						10,
						100,
						Integer.MAX_VALUE,
						ArenaReserveReleaseMath.waitingRemainingSlots(25, 23)));
	}

	@Test
	void active25ReservePositiveReleasesZero() {
		assertEquals(
				0,
				ArenaReserveReleaseMath.computeActualRelease(
						10,
						50,
						Integer.MAX_VALUE,
						ArenaReserveReleaseMath.waitingRemainingSlots(25, 25)));
		assertEquals(
				0,
				ArenaReserveReleaseMath.computeActualRelease(
						100,
						50,
						Integer.MAX_VALUE,
						ArenaReserveReleaseMath.waitingRemainingSlots(25, 25)));
	}

	@Test
	void giftAfterCapStaysInReserveMath() {
		// At cap: wave releases 0; reserve is unchanged by the release formula.
		int reserve = 40;
		int release = ArenaReserveReleaseMath.computeActualRelease(
				10,
				reserve,
				Integer.MAX_VALUE,
				ArenaReserveReleaseMath.waitingRemainingSlots(25, 25));
		assertEquals(0, release);
		assertEquals(40, reserve);
		// Gift adds to reserve only (acceptFighter path) — simulated:
		reserve += 5;
		assertEquals(45, reserve);
		assertEquals(
				0,
				ArenaReserveReleaseMath.computeActualRelease(
						10,
						reserve,
						Integer.MAX_VALUE,
						ArenaReserveReleaseMath.waitingRemainingSlots(25, 25)));
	}

	@Test
	void battleDoesNotApplyWaitingCap() {
		assertEquals(
				10,
				ArenaReserveReleaseMath.computeActualRelease(10, 60, Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertEquals(
				60,
				ArenaReserveReleaseMath.computeActualRelease(100, 60, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test
	void sequentialReleasesNeverExceed25() {
		int field = 23;
		int reserve = 100;
		// First event same tick: releases 2 → field 25.
		int first = ArenaReserveReleaseMath.computeActualRelease(
				10,
				reserve,
				Integer.MAX_VALUE,
				ArenaReserveReleaseMath.waitingRemainingSlots(25, field));
		assertEquals(2, first);
		field += first;
		reserve -= first;
		// Second event with updated living count: must release 0 (not another 2).
		int second = ArenaReserveReleaseMath.computeActualRelease(
				10,
				reserve,
				Integer.MAX_VALUE,
				ArenaReserveReleaseMath.waitingRemainingSlots(25, field));
		assertEquals(0, second);
		assertEquals(25, field);
	}

	@Test
	void waitingRemainingSlotsHelpers() {
		assertEquals(25, ArenaReserveReleaseMath.waitingRemainingSlots(25, 0));
		assertEquals(2, ArenaReserveReleaseMath.waitingRemainingSlots(25, 23));
		assertEquals(0, ArenaReserveReleaseMath.waitingRemainingSlots(25, 25));
		assertEquals(0, ArenaReserveReleaseMath.waitingRemainingSlots(25, 30));
		assertEquals(0, ArenaReserveReleaseMath.waitingRemainingSlots(0, 0));
	}

	@Test
	void matchManagerWiresWaitingWavesAndLimit() throws Exception {
		String source = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchManager.java"),
				StandardCharsets.UTF_8);
		assertTrue(source.contains("WAITING_FIELD_LIMIT"));
		assertTrue(source.contains("waitingRemainingSlots"));
		assertTrue(source.contains("countLivingFightersUncached"));
		assertTrue(source.contains("releaseReserveWaves(level)"));
		// Waves must run from tickWaiting, not only tickBattle.
		int waitingIdx = source.indexOf("private void tickWaiting");
		int battleIdx = source.indexOf("private void tickBattle");
		assertTrue(waitingIdx >= 0 && battleIdx > waitingIdx);
		String waitingBody = source.substring(waitingIdx, battleIdx);
		assertTrue(waitingBody.contains("releaseReserveWaves"));
		assertTrue(waitingBody.contains("battleTicksElapsed++"));
	}

	@Test
	void startBattleDoesNotClearExistingFighters() throws Exception {
		String source = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchManager.java"),
				StandardCharsets.UTF_8);
		int start = source.indexOf("private void startBattle");
		int end = source.indexOf("private int resolveBattleDurationSeconds");
		assertTrue(start >= 0 && end > start);
		String body = source.substring(start, end);
		assertTrue(body.contains("ArenaMatchState.BATTLE"));
		assertFalse(body.contains("clearAllFighters"));
		assertFalse(body.contains("clearReserves"));
	}

	@Test
	void simulateWavesArrayShape() {
		assertArrayEquals(
				new int[] {10, 10, 5, 0, 0},
				trimTrailingZerosPad(ArenaReserveReleaseMath.simulateWaitingWaves(10, 60, 0, 5), 5));
	}

	private static int[] trimTrailingZerosPad(int[] waves, int len) {
		int[] out = new int[len];
		System.arraycopy(waves, 0, out, 0, Math.min(waves.length, len));
		return out;
	}
}
