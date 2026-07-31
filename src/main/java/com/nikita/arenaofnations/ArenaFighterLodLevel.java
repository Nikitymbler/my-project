package com.nikita.arenaofnations;

/**
 * Client-only fighter LOD band used by render decision logic.
 * Does not affect server combat.
 */
public enum ArenaFighterLodLevel {
	NEAR,
	MID,
	FAR,
	CULLED
}
