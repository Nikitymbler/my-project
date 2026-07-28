package com.nikita.arenaofnations;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * Tier-based incoming damage scaling between arena fighters.
 */
public final class ArenaTierDamageScaling {
	private ArenaTierDamageScaling() {
	}

	public static float scaleIncomingDamage(ArenaFighterEntity victim, DamageSource source, float amount) {
		if (amount <= 0.0F || victim.level().isClientSide) {
			return amount;
		}

		Entity attackerEntity = source.getEntity();
		if (!(attackerEntity instanceof ArenaFighterEntity attacker)) {
			return amount;
		}
		if (!FighterFactory.isArenaFighter(victim) || !FighterFactory.isArenaFighter(attacker)) {
			return amount;
		}

		Country victimCountry = victim.getArenaCountry();
		Country attackerCountry = attacker.getArenaCountry();
		if (victimCountry == null || attackerCountry == null || victimCountry == attackerCountry) {
			return amount;
		}

		float multiplier = getDamageMultiplier(attacker.getArenaTier(), victim.getArenaTier());
		if (multiplier >= 1.0F) {
			return amount;
		}
		return amount * multiplier;
	}

	public static float getDamageMultiplier(FighterTier attacker, FighterTier defender) {
		if (attacker == null || defender == null) {
			return 1.0F;
		}

		if (defender == FighterTier.TITAN) {
			return switch (attacker) {
				case SCOUT -> 0.15F;
				case WARRIOR -> 0.30F;
				case HEAVY -> 0.50F;
				case HERO -> 0.75F;
				case TITAN -> 1.00F;
			};
		}

		int gap = defender.ordinal() - attacker.ordinal();
		if (gap <= 0) {
			return 1.00F;
		}
		return switch (gap) {
			case 1 -> 0.75F;
			case 2 -> 0.50F;
			case 3 -> 0.30F;
			default -> 0.15F;
		};
	}
}
