package com.nikita.arenaofnations;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable round HUD / base-marker data shared between server synchronizer and client.
 */
public final class ArenaHudSnapshot {
	public static final ArenaHudSnapshot EMPTY =
			new ArenaHudSnapshot(ArenaMatchState.IDLE, 0, ArenaHudDisplayMode.OFF, 0, null, 0, 0, 0, false, List.of());

	private final ArenaMatchState state;
	private final int remainingTicks;
	private final ArenaHudDisplayMode mode;
	private final int activeCountryCount;
	private final String rescueCountryCode;
	private final int arenaCenterX;
	private final int arenaCenterY;
	private final int arenaCenterZ;
	private final boolean arenaCenterValid;
	private final List<ArenaHudCountryState> countries;

	public ArenaHudSnapshot(
			ArenaMatchState state,
			int remainingTicks,
			ArenaHudDisplayMode mode,
			int activeCountryCount,
			String rescueCountryCode,
			int arenaCenterX,
			int arenaCenterY,
			int arenaCenterZ,
			boolean arenaCenterValid,
			List<ArenaHudCountryState> countries) {
		this.state = state == null ? ArenaMatchState.IDLE : state;
		this.remainingTicks = Math.max(0, remainingTicks);
		this.mode = mode == null ? ArenaHudDisplayMode.EXTERNAL : mode;
		this.activeCountryCount = Math.max(0, activeCountryCount);
		this.rescueCountryCode = rescueCountryCode;
		this.arenaCenterX = arenaCenterX;
		this.arenaCenterY = arenaCenterY;
		this.arenaCenterZ = arenaCenterZ;
		this.arenaCenterValid = arenaCenterValid;
		this.countries = List.copyOf(countries);
	}

	public ArenaMatchState state() {
		return state;
	}

	public int remainingTicks() {
		return remainingTicks;
	}

	public ArenaHudDisplayMode mode() {
		return mode;
	}

	public int activeCountryCount() {
		return activeCountryCount;
	}

	public String rescueCountryCode() {
		return rescueCountryCode;
	}

	public int arenaCenterX() {
		return arenaCenterX;
	}

	public int arenaCenterY() {
		return arenaCenterY;
	}

	public int arenaCenterZ() {
		return arenaCenterZ;
	}

	public boolean arenaCenterValid() {
		return arenaCenterValid;
	}

	public List<ArenaHudCountryState> countries() {
		return countries;
	}

	public boolean shouldDisplay() {
		return state != ArenaMatchState.IDLE;
	}

	public String formatStatusText() {
		return switch (state) {
			case IDLE -> "ЗАВЕРШЕНО";
			case WAITING_FOR_OPPONENT -> "ОЖИДАНИЕ";
			case BATTLE -> "БОЙ";
			case BREAK -> "ПЕРЕРЫВ";
		};
	}

	public String formatTimerMmSs() {
		int remainingSeconds = (remainingTicks + 19) / 20;
		return String.format(Locale.ROOT, "%02d:%02d", remainingSeconds / 60, remainingSeconds % 60);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ArenaHudSnapshot snapshot)) {
			return false;
		}
		return remainingTicks == snapshot.remainingTicks
				&& state == snapshot.state
				&& mode == snapshot.mode
				&& activeCountryCount == snapshot.activeCountryCount
				&& arenaCenterX == snapshot.arenaCenterX
				&& arenaCenterY == snapshot.arenaCenterY
				&& arenaCenterZ == snapshot.arenaCenterZ
				&& arenaCenterValid == snapshot.arenaCenterValid
				&& Objects.equals(rescueCountryCode, snapshot.rescueCountryCode)
				&& countries.equals(snapshot.countries);
	}

	@Override
	public int hashCode() {
		int result = state.hashCode();
		result = 31 * result + remainingTicks;
		result = 31 * result + mode.hashCode();
		result = 31 * result + activeCountryCount;
		result = 31 * result + Objects.hashCode(rescueCountryCode);
		result = 31 * result + arenaCenterX;
		result = 31 * result + arenaCenterY;
		result = 31 * result + arenaCenterZ;
		result = 31 * result + Boolean.hashCode(arenaCenterValid);
		return 31 * result + countries.hashCode();
	}
}
