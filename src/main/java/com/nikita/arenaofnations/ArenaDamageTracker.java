package com.nikita.arenaofnations;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Helpers for arena damage accounting. Actual HP loss is measured by
 * {@link com.nikita.arenaofnations.mixin.LivingEntityHurtMixin}.
 */
public final class ArenaDamageTracker {
	private ArenaDamageTracker() {
	}

	static void register() {
		// Damage is tracked via LivingEntityHurtMixin, not Fabric ALLOW_DAMAGE.
	}

	public static Country resolveAttackerCountry(LivingEntity victim, DamageSource source) {
		if (!FighterFactory.isArenaFighter(victim)) {
			return null;
		}

		Country victimCountry = FighterFactory.getCountry(victim);
		if (victimCountry == null) {
			return null;
		}

		Entity attackerEntity = source.getEntity();
		if (!(attackerEntity instanceof LivingEntity attacker) || attacker instanceof Player) {
			return null;
		}

		if (!FighterFactory.isArenaFighter(attacker)) {
			return null;
		}

		Country attackerCountry = FighterFactory.getCountry(attacker);
		if (attackerCountry == null || attackerCountry == victimCountry) {
			return null;
		}

		return attackerCountry;
	}

	public static float totalVitality(LivingEntity entity) {
		return entity.getHealth() + entity.getAbsorptionAmount();
	}

	public static void creditDamage(Country attackerCountry, float lost) {
		if (attackerCountry == null || lost <= 0.0F) {
			return;
		}
		ArenaMatchManager.get().addDamage(attackerCountry, lost);
	}
}
