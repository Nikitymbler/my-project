package com.nikita.arenaofnations;

import java.util.Locale;

public enum FighterTier {
	SCOUT("scout", "Боец"),
	WARRIOR("warrior", "Воин"),
	HEAVY("heavy", "Тяжёлый боец"),
	HERO("hero", "Герой"),
	TITAN("titan", "Титан");

	private final String id;
	private final String displayName;

	FighterTier(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public int getGiftCost() {
		return ArenaFighterBalance.giftCost(this);
	}

	public int getEffectiveCombatValue() {
		return ArenaFighterBalance.effectiveCombatValue(this);
	}

	public double getMaxHealth() {
		return ArenaFighterBalance.get(this).maxHealth();
	}

	public double getAttackDamage() {
		return ArenaFighterBalance.get(this).attackDamage();
	}

	public double getMovementSpeed() {
		return ArenaFighterBalance.get(this).movementSpeed();
	}

	public double getKnockbackResistance() {
		return ArenaFighterBalance.get(this).knockbackResistance();
	}

	public int getAttackCooldownTicks() {
		return ArenaFighterBalance.get(this).attackCooldownTicks();
	}

	public double getScale() {
		return ArenaFighterBalance.get(this).visualScale();
	}

	public String getAbilityName() {
		return ArenaFighterBalance.get(this).abilityName();
	}

	public String tierTag() {
		return "tier_" + id;
	}

	public static FighterTier byId(String raw) {
		String id = raw.toLowerCase(Locale.ROOT);
		for (FighterTier tier : values()) {
			if (tier.id.equals(id)) {
				return tier;
			}
		}
		return null;
	}
}
