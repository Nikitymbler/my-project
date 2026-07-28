package com.nikita.arenaofnations;

/**
 * One country's server-authoritative state for the client round HUD.
 * {@code eliminated} = final knockout; {@code rescuing} = countdown (0 fighters + core down);
 * core HP 0 with fighters alive is shown as «ЯДРО СБИТО» on the client without {@code rescuing}.
 */
public record ArenaHudCountryState(
		Country country,
		int baseSlot,
		int aliveFighters,
		float coreHealth,
		float coreMaxHealth,
		int reserveCount,
		boolean eliminated,
		boolean rescuing,
		int rescueSecondsRemaining,
		boolean holder,
		boolean coreProtected) {
}
