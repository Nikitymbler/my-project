package com.nikita.arenaofnations;

import java.util.ArrayDeque;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * CHAMPION (internal {@link FighterTier#HERO}) ability: every 3rd successful melee hit heals attacker.
 */
final class ArenaChampionVampiricStrikeAbility {
	private static final int HITS_BEFORE_VAMPIRIC = 2;
	private static final int RECENT_WINDOW_TICKS = 200;
	private static final float HEAL_RATIO = 0.50F;
	private static final float MAX_HEAL_PER_TRIGGER = 6.0F;

	private static final ArrayDeque<VampiricEvent> RECENT_VAMPIRIC_EVENTS = new ArrayDeque<>();

	private ArenaChampionVampiricStrikeAbility() {
	}

	static boolean handleDoHurtTarget(
			ArenaFighterEntity attacker,
			Entity target,
			ArenaEliteHeavyStrikeAbility.VanillaHurt vanillaHurt) {
		if (attacker.level().isClientSide) {
			return vanillaHurt.apply(target);
		}
		if (!isChampion(attacker) || !isValidEnemyFighterTarget(attacker, target)) {
			return vanillaHurt.apply(target);
		}

		LivingEntity defender = (LivingEntity) target;
		float beforeVitality = totalVitality(defender);
		boolean success = vanillaHurt.apply(target);
		if (!success) {
			return false;
		}

		float actualDamage = Math.max(0.0F, beforeVitality - totalVitality(defender));
		if (actualDamage <= 0.0F) {
			return true;
		}

		boolean vampiricReady = attacker.getChampionVampiricStrikeProgress() >= HITS_BEFORE_VAMPIRIC;
		if (!vampiricReady) {
			attacker.incrementChampionVampiricStrikeProgress();
			return true;
		}

		float rawHealing = actualDamage * HEAL_RATIO;
		float finalHealing = Math.min(rawHealing, MAX_HEAL_PER_TRIGGER);
		float beforeHeal = attacker.getHealth();
		if (finalHealing > 0.0F) {
			attacker.heal(finalHealing);
		}
		float actualHealing = Math.max(0.0F, attacker.getHealth() - beforeHeal);

		attacker.resetChampionVampiricStrikeProgress();
		long gameTime = attacker.level().getGameTime();
		attacker.recordChampionVampiricStrike(gameTime, actualHealing);
		recordRecentVampiricEvent(gameTime, actualHealing);
		playVampiricStrikeEffects(attacker, defender, actualHealing);
		return true;
	}

	static boolean isChampion(ArenaFighterEntity fighter) {
		return fighter != null && fighter.getArenaTier() == FighterTier.HERO;
	}

	private static boolean isValidEnemyFighterTarget(ArenaFighterEntity attacker, Entity target) {
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

	private static float totalVitality(LivingEntity entity) {
		return entity.getHealth() + entity.getAbsorptionAmount();
	}

	private static void playVampiricStrikeEffects(ArenaFighterEntity attacker, LivingEntity defender, float actualHealing) {
		if (!(attacker.level() instanceof ServerLevel level)) {
			return;
		}

		double tx = defender.getX();
		double ty = defender.getY() + defender.getBbHeight() * 0.55;
		double tz = defender.getZ();
		double ax = attacker.getX();
		double ay = attacker.getY() + attacker.getBbHeight() * 0.65;
		double az = attacker.getZ();

		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, tx, ty, tz, 6, 0.30, 0.22, 0.30, 0.06);
		level.sendParticles(ParticleTypes.ENCHANTED_HIT, ax, ay, az, 6, 0.32, 0.28, 0.32, 0.02);
		// Always show heal cue on trigger (readable even at full HP when actualHealing == 0).
		level.sendParticles(ParticleTypes.HEART, ax, ay, az, 8, 0.35, 0.30, 0.35, 0.02);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, ax, ay, az, 10, 0.40, 0.35, 0.40, 0.03);

		level.playSound(null, tx, defender.getY(), tz, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 0.70F, 0.90F);
		level.playSound(
				null,
				ax,
				attacker.getY(),
				az,
				SoundEvents.EXPERIENCE_ORB_PICKUP,
				SoundSource.HOSTILE,
				0.55F,
				1.05F + level.random.nextFloat() * 0.10F);
		level.playSound(
				null,
				ax,
				attacker.getY(),
				az,
				SoundEvents.ALLAY_ITEM_TAKEN,
				SoundSource.HOSTILE,
				0.45F,
				1.15F);
	}

	private static void recordRecentVampiricEvent(long gameTime, float actualHealing) {
		synchronized (RECENT_VAMPIRIC_EVENTS) {
			RECENT_VAMPIRIC_EVENTS.addLast(new VampiricEvent(gameTime, actualHealing));
			pruneRecent(gameTime);
		}
	}

	private static void pruneRecent(long gameTime) {
		while (!RECENT_VAMPIRIC_EVENTS.isEmpty()
				&& gameTime - RECENT_VAMPIRIC_EVENTS.peekFirst().gameTime() > RECENT_WINDOW_TICKS) {
			RECENT_VAMPIRIC_EVENTS.removeFirst();
		}
	}

	static RecentVampiricStats recentStats(long gameTime) {
		synchronized (RECENT_VAMPIRIC_EVENTS) {
			pruneRecent(gameTime);
			int triggers = RECENT_VAMPIRIC_EVENTS.size();
			float healing = 0.0F;
			for (VampiricEvent event : RECENT_VAMPIRIC_EVENTS) {
				healing += event.actualHealing();
			}
			return new RecentVampiricStats(triggers, healing);
		}
	}

	static void appendChampionStatus(StringBuilder builder, ServerLevel level) {
		int liveChampion = 0;
		int[] progressCounts = new int[3];
		int totalTriggers = 0;
		float totalHealing = 0.0F;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| !isChampion(fighter)) {
				continue;
			}
			liveChampion++;
			int progress = Math.min(2, Math.max(0, fighter.getChampionVampiricStrikeProgress()));
			progressCounts[progress]++;
			totalTriggers += fighter.getChampionVampiricStrikeTriggerCount();
			totalHealing += fighter.getChampionTotalVampiricHealing();
		}

		RecentVampiricStats recent = recentStats(level.getGameTime());
		builder.append('\n')
				.append('\n')
				.append("CHAMPION — Вампирский удар (tier HERO)\n")
				.append("живых CHAMPION=").append(liveChampion).append('\n')
				.append("progress 0=").append(progressCounts[0]).append('\n')
				.append("progress 1=").append(progressCounts[1]).append('\n')
				.append("progress 2 (готов вампирский)=").append(progressCounts[2]).append('\n')
				.append("вампирских ударов у живых CHAMPION=").append(totalTriggers).append('\n')
				.append("вампирских ударов за ~10с=").append(recent.triggers()).append('\n')
				.append("восстановлено HP живыми CHAMPION=").append(formatHp(totalHealing)).append('\n')
				.append("восстановлено HP за ~10с=").append(formatHp(recent.healing()));
	}

	private static String formatHp(float hp) {
		return String.format(java.util.Locale.ROOT, "%.2f", hp);
	}

	private record VampiricEvent(long gameTime, float actualHealing) {
	}

	record RecentVampiricStats(int triggers, float healing) {
	}
}
