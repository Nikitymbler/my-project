package com.nikita.arenaofnations;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

/**
 * TITAN (internal {@link FighterTier#TITAN}) ability: every 2nd successful melee hit triggers shockwave.
 */
final class ArenaTitanShockwaveAbility {
	private static final int HITS_BEFORE_SHOCKWAVE = 1;
	private static final int MAX_SECONDARY_TARGETS = 4;
	private static final float SHOCKWAVE_RADIUS = 3.5F;
	private static final float MAX_VERTICAL_DELTA = 1.75F;
	private static final float WAVE_DAMAGE_SCALE = 0.40F;
	private static final float WAVE_DAMAGE_CAP = 6.0F;
	private static final int RECENT_WINDOW_TICKS = 200;

	private static final ArrayDeque<ShockwaveEvent> RECENT_SHOCKWAVES = new ArrayDeque<>();

	private ArenaTitanShockwaveAbility() {
	}

	static boolean handleDoHurtTarget(
			ArenaFighterEntity attacker,
			Entity target,
			ArenaEliteHeavyStrikeAbility.VanillaHurt vanillaHurt) {
		if (attacker.level().isClientSide) {
			return vanillaHurt.apply(target);
		}
		if (!isTitan(attacker) || !isValidEnemyFighterTarget(attacker, target)) {
			return vanillaHurt.apply(target);
		}

		LivingEntity mainTarget = (LivingEntity) target;
		float beforeVitality = totalVitality(mainTarget);
		boolean success = vanillaHurt.apply(target);
		if (!success) {
			return false;
		}

		float actualDamage = Math.max(0.0F, beforeVitality - totalVitality(mainTarget));
		if (actualDamage <= 0.0F) {
			return true;
		}

		boolean shockwaveReady = attacker.getTitanShockwaveProgress() >= HITS_BEFORE_SHOCKWAVE;
		if (!shockwaveReady) {
			attacker.incrementTitanShockwaveProgress();
			return true;
		}

		float waveDamage = computeWaveDamage(attacker);
		ShockwaveResult result = applyShockwave(attacker, mainTarget, waveDamage);
		attacker.resetTitanShockwaveProgress();
		long gameTime = attacker.level().getGameTime();
		attacker.recordTitanShockwave(gameTime, result.secondaryHits(), result.actualDamage());
		recordRecentShockwave(gameTime, result.secondaryHits(), result.actualDamage());
		playShockwaveEffects(attacker, mainTarget, result.hitTargets());
		return true;
	}

	static boolean isTitan(ArenaFighterEntity fighter) {
		return fighter != null && fighter.getArenaTier() == FighterTier.TITAN;
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

	private static float computeWaveDamage(ArenaFighterEntity attacker) {
		float scaled = (float) (attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) * WAVE_DAMAGE_SCALE);
		return Math.min(scaled, WAVE_DAMAGE_CAP);
	}

	private static ShockwaveResult applyShockwave(ArenaFighterEntity attacker, LivingEntity mainTarget, float waveDamage) {
		if (!(attacker.level() instanceof ServerLevel level) || waveDamage <= 0.0F) {
			return new ShockwaveResult(0, 0.0F, List.of());
		}

		AABB area = attacker.getBoundingBox().inflate(SHOCKWAVE_RADIUS, MAX_VERTICAL_DELTA, SHOCKWAVE_RADIUS);
		Country attackerCountry = attacker.getArenaCountry();
		List<ArenaFighterEntity> candidates = level.getEntitiesOfClass(
				ArenaFighterEntity.class,
				area,
				candidate -> candidate.isAlive()
						&& !candidate.isRemoved()
						&& candidate != attacker
						&& candidate != mainTarget
						&& FighterFactory.isArenaFighter(candidate)
						&& candidate.getArenaCountry() != null
						&& attackerCountry != null
						&& candidate.getArenaCountry() != attackerCountry
						&& Math.abs(candidate.getY() - attacker.getY()) <= MAX_VERTICAL_DELTA
						&& horizontalDistanceSqr(attacker, candidate) <= SHOCKWAVE_RADIUS * SHOCKWAVE_RADIUS
						&& attacker.hasLineOfSight(candidate));

		candidates.sort(Comparator.comparingDouble(candidate -> attacker.distanceToSqr(candidate)));
		if (candidates.size() > MAX_SECONDARY_TARGETS) {
			candidates = candidates.subList(0, MAX_SECONDARY_TARGETS);
		}

		int hitCount = 0;
		float totalDamage = 0.0F;
		ArrayDeque<Entity> hitTargets = new ArrayDeque<>();
		for (ArenaFighterEntity secondary : candidates) {
			float beforeVitality = totalVitality(secondary);
			boolean hurt = secondary.hurt(attacker.damageSources().mobAttack(attacker), waveDamage);
			if (!hurt) {
				continue;
			}
			float lost = Math.max(0.0F, beforeVitality - totalVitality(secondary));
			if (lost <= 0.0F) {
				continue;
			}
			hitCount++;
			totalDamage += lost;
			hitTargets.add(secondary);
		}
		return new ShockwaveResult(hitCount, totalDamage, List.copyOf(hitTargets));
	}

	private static float totalVitality(LivingEntity entity) {
		return entity.getHealth() + entity.getAbsorptionAmount();
	}

	private static double horizontalDistanceSqr(LivingEntity a, LivingEntity b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private static void playShockwaveEffects(ArenaFighterEntity attacker, LivingEntity mainTarget, List<Entity> hitTargets) {
		if (!(attacker.level() instanceof ServerLevel level)) {
			return;
		}

		double ax = attacker.getX();
		double ay = attacker.getY() + 0.18;
		double az = attacker.getZ();

		// Outer readable ring + softer inner second layer (no screen-filling blast).
		spawnRing(level, ParticleTypes.CLOUD, ax, ay, az, 3.15, 36);
		spawnRing(level, ParticleTypes.SMOKE, ax, ay + 0.08, az, 2.35, 28);
		level.sendParticles(ParticleTypes.POOF, ax, ay + 0.20, az, 14, 0.55, 0.14, 0.55, 0.03);

		double tx = mainTarget.getX();
		double ty = mainTarget.getY() + mainTarget.getBbHeight() * 0.50;
		double tz = mainTarget.getZ();
		level.sendParticles(ParticleTypes.CRIT, tx, ty, tz, 8, 0.32, 0.24, 0.32, 0.08);

		for (Entity hitTarget : hitTargets) {
			if (!(hitTarget instanceof LivingEntity living)) {
				continue;
			}
			level.sendParticles(
					ParticleTypes.DAMAGE_INDICATOR,
					living.getX(),
					living.getY() + living.getBbHeight() * 0.5,
					living.getZ(),
					5,
					0.22,
					0.18,
					0.22,
					0.04);
		}

		level.playSound(null, ax, attacker.getY(), az, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.00F, 0.62F);
		// Soft boom — keep volume modest so it reads as a wave, not a screen explosion.
		level.playSound(null, ax, attacker.getY(), az, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.22F, 0.72F);
	}

	private static void spawnRing(
			ServerLevel level,
			net.minecraft.core.particles.SimpleParticleType particle,
			double ax,
			double ay,
			double az,
			double radius,
			int points) {
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2.0 * i) / points;
			double x = ax + Math.cos(angle) * radius;
			double z = az + Math.sin(angle) * radius;
			level.sendParticles(particle, x, ay, z, 1, 0.05, 0.02, 0.05, 0.01);
		}
	}

	private static void recordRecentShockwave(long gameTime, int secondaryHits, float actualDamage) {
		synchronized (RECENT_SHOCKWAVES) {
			RECENT_SHOCKWAVES.addLast(new ShockwaveEvent(gameTime, secondaryHits, actualDamage));
			pruneRecent(gameTime);
		}
	}

	private static void pruneRecent(long gameTime) {
		while (!RECENT_SHOCKWAVES.isEmpty() && gameTime - RECENT_SHOCKWAVES.peekFirst().gameTime() > RECENT_WINDOW_TICKS) {
			RECENT_SHOCKWAVES.removeFirst();
		}
	}

	static RecentShockwaveStats recentStats(long gameTime) {
		synchronized (RECENT_SHOCKWAVES) {
			pruneRecent(gameTime);
			int triggers = RECENT_SHOCKWAVES.size();
			int secondaryHits = 0;
			float damage = 0.0F;
			for (ShockwaveEvent event : RECENT_SHOCKWAVES) {
				secondaryHits += event.secondaryHits();
				damage += event.actualDamage();
			}
			return new RecentShockwaveStats(triggers, secondaryHits, damage);
		}
	}

	static void appendTitanStatus(StringBuilder builder, ServerLevel level) {
		int liveTitan = 0;
		int[] progressCounts = new int[2];
		int totalTriggers = 0;
		int totalSecondaryHits = 0;
		float totalDamage = 0.0F;

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| !isTitan(fighter)) {
				continue;
			}
			liveTitan++;
			int progress = Math.min(1, Math.max(0, fighter.getTitanShockwaveProgress()));
			progressCounts[progress]++;
			totalTriggers += fighter.getTitanShockwaveTriggerCount();
			totalSecondaryHits += fighter.getTitanShockwaveSecondaryHitCount();
			totalDamage += fighter.getTitanTotalShockwaveDamage();
		}

		RecentShockwaveStats recent = recentStats(level.getGameTime());
		builder.append('\n')
				.append('\n')
				.append("TITAN — Ударная волна (tier TITAN)\n")
				.append("живых TITAN=").append(liveTitan).append('\n')
				.append("progress 0=").append(progressCounts[0]).append('\n')
				.append("progress 1 (следующий удар запускает волну)=").append(progressCounts[1]).append('\n')
				.append("ударных волн у живых TITAN=").append(totalTriggers).append('\n')
				.append("ударных волн за ~10с=").append(recent.triggers()).append('\n')
				.append("доп. целей поражено=").append(totalSecondaryHits).append('\n')
				.append("фактический урон волной=").append(formatHp(totalDamage)).append('\n')
				.append("урон волной за ~10с=").append(formatHp(recent.damage()));
	}

	private static String formatHp(float hp) {
		return String.format(java.util.Locale.ROOT, "%.2f", hp);
	}

	private record ShockwaveEvent(long gameTime, int secondaryHits, float actualDamage) {
	}

	private record ShockwaveResult(int secondaryHits, float actualDamage, List<Entity> hitTargets) {
	}

	record RecentShockwaveStats(int triggers, int secondaryHits, float damage) {
	}
}
