package com.nikita.arenaofnations;

import java.util.ArrayDeque;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
/**
 * ELITE (internal {@link FighterTier#HEAVY}) ability: every 4th successful melee hit deals 160% damage.
 */
final class ArenaEliteHeavyStrikeAbility {
	private static final ResourceLocation HEAVY_STRIKE_MODIFIER_ID =
			ArenaOfNations.id("elite_heavy_strike");
	private static final double HEAVY_DAMAGE_BONUS = 0.60D;
	private static final int HITS_BEFORE_HEAVY = 3;
	private static final int RECENT_WINDOW_TICKS = 200;

	private static final ArrayDeque<Long> RECENT_HEAVY_STRIKE_TIMES = new ArrayDeque<>();

	private ArenaEliteHeavyStrikeAbility() {
	}

	/**
	 * Handles one {@link ArenaFighterEntity#doHurtTarget} call: optionally boosts ATTACK_DAMAGE,
	 * invokes vanilla hurt exactly once, then updates elite progress only on success.
	 */
	static boolean handleDoHurtTarget(ArenaFighterEntity attacker, Entity target, VanillaHurt vanillaHurt) {
		if (attacker.level().isClientSide) {
			return vanillaHurt.apply(target);
		}

		if (!isElite(attacker) || !isValidEnemyFighterTarget(attacker, target)) {
			return vanillaHurt.apply(target);
		}

		boolean heavyReady = attacker.getEliteHeavyStrikeProgress() >= HITS_BEFORE_HEAVY;
		AttributeInstance attackDamage = attacker.getAttribute(Attributes.ATTACK_DAMAGE);
		boolean modifierApplied = false;

		try {
			if (heavyReady && attackDamage != null) {
				AttributeModifier modifier = new AttributeModifier(
						HEAVY_STRIKE_MODIFIER_ID,
						HEAVY_DAMAGE_BONUS,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
				attackDamage.addOrUpdateTransientModifier(modifier);
				modifierApplied = true;
			}

			boolean success = vanillaHurt.apply(target);

			if (success) {
				if (heavyReady) {
					attacker.resetEliteHeavyStrikeProgress();
					attacker.recordEliteHeavyStrike(attacker.level().getGameTime());
					playHeavyStrikeEffects(attacker, (LivingEntity) target);
					recordRecentHeavyStrike(attacker.level().getGameTime());
				} else {
					attacker.incrementEliteHeavyStrikeProgress();
				}
			}

			return success;
		} finally {
			if (modifierApplied && attackDamage != null) {
				attackDamage.removeModifier(HEAVY_STRIKE_MODIFIER_ID);
			}
		}
	}

	static boolean isElite(ArenaFighterEntity fighter) {
		return fighter != null && fighter.getArenaTier() == FighterTier.HEAVY;
	}

	static boolean isValidEnemyFighterTarget(ArenaFighterEntity attacker, Entity target) {
		if (!(target instanceof ArenaFighterEntity defender) || !defender.isAlive() || defender.isRemoved()) {
			return false;
		}
		if (!FighterFactory.isArenaFighter(attacker) || !FighterFactory.isArenaFighter(defender)) {
			return false;
		}
		if (attacker == defender) {
			return false;
		}
		Country attackerCountry = attacker.getArenaCountry();
		Country defenderCountry = defender.getArenaCountry();
		return attackerCountry != null && defenderCountry != null && attackerCountry != defenderCountry;
	}

	private static void playHeavyStrikeEffects(ArenaFighterEntity attacker, LivingEntity target) {
		if (!(attacker.level() instanceof ServerLevel level)) {
			return;
		}

		double x = target.getX();
		double y = target.getY() + target.getBbHeight() * 0.55;
		double z = target.getZ();

		level.sendParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 5, 0.45, 0.25, 0.45, 0.0);
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 22, 0.55, 0.40, 0.55, 0.35);
		level.sendParticles(ParticleTypes.POOF, x, y, z, 8, 0.35, 0.28, 0.35, 0.03);
		level.sendParticles(ParticleTypes.SMOKE, x, y, z, 4, 0.25, 0.20, 0.25, 0.01);

		level.playSound(
				null,
				target.getX(),
				target.getY(),
				target.getZ(),
				SoundEvents.PLAYER_ATTACK_CRIT,
				SoundSource.HOSTILE,
				1.05F,
				0.62F + level.random.nextFloat() * 0.06F);
		level.playSound(
				null,
				target.getX(),
				target.getY(),
				target.getZ(),
				SoundEvents.PLAYER_ATTACK_STRONG,
				SoundSource.HOSTILE,
				0.90F,
				0.55F + level.random.nextFloat() * 0.08F);
	}

	private static void recordRecentHeavyStrike(long gameTime) {
		synchronized (RECENT_HEAVY_STRIKE_TIMES) {
			RECENT_HEAVY_STRIKE_TIMES.addLast(gameTime);
			pruneRecent(gameTime);
		}
	}

	private static void pruneRecent(long gameTime) {
		while (!RECENT_HEAVY_STRIKE_TIMES.isEmpty()
				&& gameTime - RECENT_HEAVY_STRIKE_TIMES.peekFirst() > RECENT_WINDOW_TICKS) {
			RECENT_HEAVY_STRIKE_TIMES.removeFirst();
		}
	}

	static int recentHeavyStrikeCount(long gameTime) {
		synchronized (RECENT_HEAVY_STRIKE_TIMES) {
			pruneRecent(gameTime);
			return RECENT_HEAVY_STRIKE_TIMES.size();
		}
	}

	static String buildClassStatusText(ServerLevel level) {
		int liveElite = 0;
		int[] progressCounts = new int[4];
		int totalTriggers = 0;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| !isElite(fighter)) {
				continue;
			}
			liveElite++;
			int progress = Math.min(3, Math.max(0, fighter.getEliteHeavyStrikeProgress()));
			progressCounts[progress]++;
			totalTriggers += fighter.getEliteHeavyStrikeTriggerCount();
		}

		long now = level.getGameTime();
		int recent = recentHeavyStrikeCount(now);

		StringBuilder builder = new StringBuilder("Классовые способности:\n"
				+ "ELITE — Тяжёлый удар (tier HEAVY)\n"
				+ "живых ELITE=" + liveElite + '\n'
				+ "progress 0=" + progressCounts[0] + '\n'
				+ "progress 1=" + progressCounts[1] + '\n'
				+ "progress 2=" + progressCounts[2] + '\n'
				+ "progress 3 (готов тяжёлый)=" + progressCounts[3] + '\n'
				+ "тяжёлых ударов у живых ELITE=" + totalTriggers + '\n'
				+ "тяжёлых ударов за ~10с=" + recent);
		ArenaChampionVampiricStrikeAbility.appendChampionStatus(builder, level);
		ArenaTitanShockwaveAbility.appendTitanStatus(builder, level);
		return builder.toString();
	}

	@FunctionalInterface
	interface VanillaHurt {
		boolean apply(Entity target);
	}
}
