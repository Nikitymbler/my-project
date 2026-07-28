package com.nikita.arenaofnations;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Remaining economic army value for timer tiebreaks.
 */
public final class ArenaEconomicArmyValue {
	public static final double VALUE_EPSILON = 0.01D;

	private ArenaEconomicArmyValue() {
	}

	public record CountryValue(
			int liveFighters,
			int reserveFighters,
			double activeGiftValue,
			double reserveGiftValue,
			double totalGiftValue,
			double activeCombatValue,
			double reserveCombatValue,
			double totalCombatValue) {
	}

	public static CountryValue compute(ServerLevel level, Country country, ArenaMatchManager match) {
		double activeGift = 0.0D;
		double activeCombat = 0.0D;
		int live = 0;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| fighter.getArenaCountry() != country) {
				continue;
			}
			live++;
			double ratio = vitalityRatio(fighter);
			FighterTier tier = fighter.getArenaTier();
			activeGift += tier.getGiftCost() * ratio;
			activeCombat += tier.getEffectiveCombatValue() * ratio;
		}

		double reserveGift = 0.0D;
		double reserveCombat = 0.0D;
		int reserveCount = 0;
		for (PendingFighter pending : match.getReserveFighters(country)) {
			reserveCount++;
			FighterTier tier = pending.getTier();
			reserveGift += tier.getGiftCost();
			reserveCombat += tier.getEffectiveCombatValue();
		}

		return new CountryValue(
				live,
				reserveCount,
				activeGift,
				reserveGift,
				activeGift + reserveGift,
				activeCombat,
				reserveCombat,
				activeCombat + reserveCombat);
	}

	private static double vitalityRatio(LivingEntity entity) {
		float maxHealth = entity.getMaxHealth();
		if (maxHealth <= 0.0F) {
			return 0.0D;
		}
		float vitality = entity.getHealth() + entity.getAbsorptionAmount();
		return Math.clamp(vitality / maxHealth, 0.0D, 1.0D);
	}
}
