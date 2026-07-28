package com.nikita.arenaofnations;

/**
 * Single server-side source for fighter economy and combat balance v1.
 */
public final class ArenaFighterBalance {
	public record TierBalance(
			int giftCost,
			int effectiveCombatValue,
			double maxHealth,
			double attackDamage,
			double movementSpeed,
			double knockbackResistance,
			int attackCooldownTicks,
			double visualScale,
			String abilityName) {
	}

	private static final TierBalance SCOUT = new TierBalance(
			1, 1, 10.0, 2.5, 0.36, 0.00, 7, 0.85, "—");
	private static final TierBalance WARRIOR = new TierBalance(
			10, 12, 30.0, 5.5, 0.31, 0.10, 20, 1.00, "—");
	private static final TierBalance HEAVY = new TierBalance(
			50, 70, 75.0, 9.0, 0.27, 0.35, 22, 1.15, "Тяжёлый удар");
	private static final TierBalance HERO = new TierBalance(
			200, 350, 160.0, 14.0, 0.29, 0.55, 22, 1.25, "Вампирский удар");
	private static final TierBalance TITAN = new TierBalance(
			1000, 1600, 360.0, 22.0, 0.23, 0.90, 26, 1.60, "Ударная волна");

	private ArenaFighterBalance() {
	}

	public static TierBalance get(FighterTier tier) {
		return switch (tier) {
			case SCOUT -> SCOUT;
			case WARRIOR -> WARRIOR;
			case HEAVY -> HEAVY;
			case HERO -> HERO;
			case TITAN -> TITAN;
		};
	}

	public static int giftCost(FighterTier tier) {
		return get(tier).giftCost();
	}

	public static int effectiveCombatValue(FighterTier tier) {
		return get(tier).effectiveCombatValue();
	}
}
