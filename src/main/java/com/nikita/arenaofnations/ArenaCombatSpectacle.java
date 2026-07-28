package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Stream-readable combat feedback for the live single-class fighter path.
 * Does not change damage, cooldown, reach, or AI — particles/sounds only.
 */
final class ArenaCombatSpectacle {
	private ArenaCombatSpectacle() {
	}

	/** Swing start: readable arc cue at the attacker. */
	static void onMeleeSwing(ServerLevel level, ArenaFighterEntity attacker) {
		if (level == null || attacker == null || !attacker.isAlive()) {
			return;
		}
		Vec3 hand = swingPoint(attacker);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, hand.x, hand.y, hand.z, 1, 0.0, 0.0, 0.0, 0.0);
		level.sendParticles(ParticleTypes.CRIT, hand.x, hand.y, hand.z, 6, 0.28, 0.18, 0.28, 0.12);
		level.playSound(
				null,
				attacker.getX(),
				attacker.getY(),
				attacker.getZ(),
				SoundEvents.PLAYER_ATTACK_SWEEP,
				SoundSource.HOSTILE,
				0.55F,
				0.95F + level.random.nextFloat() * 0.15F);
	}

	/** Confirmed melee hit on another fighter. */
	static void onMeleeHit(ServerLevel level, ArenaFighterEntity attacker, LivingEntity target) {
		if (level == null || attacker == null || target == null) {
			return;
		}
		double x = target.getX();
		double y = target.getY() + target.getBbHeight() * 0.65;
		double z = target.getZ();
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, x, y, z, 8, 0.28, 0.22, 0.28, 0.08);
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 10, 0.35, 0.28, 0.35, 0.22);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		level.playSound(
				null,
				x,
				target.getY(),
				z,
				SoundEvents.PLAYER_ATTACK_STRONG,
				SoundSource.HOSTILE,
				0.70F,
				0.90F + level.random.nextFloat() * 0.15F);
	}

	/** Fighter death — readable from streaming camera distance. */
	static void onFighterDeath(ServerLevel level, ArenaFighterEntity fighter) {
		if (level == null || fighter == null) {
			return;
		}
		double x = fighter.getX();
		double y = fighter.getY() + fighter.getBbHeight() * 0.55;
		double z = fighter.getZ();
		level.sendParticles(ParticleTypes.POOF, x, y, z, 18, 0.35, 0.40, 0.35, 0.04);
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 14, 0.30, 0.35, 0.30, 0.25);
		level.sendParticles(ParticleTypes.SMOKE, x, y, z, 8, 0.22, 0.28, 0.22, 0.015);
		level.sendParticles(ParticleTypes.CLOUD, x, y, z, 6, 0.25, 0.20, 0.25, 0.02);
		level.playSound(
				null,
				x,
				y,
				z,
				SoundEvents.PLAYER_ATTACK_CRIT,
				SoundSource.NEUTRAL,
				0.85F,
				0.75F + level.random.nextFloat() * 0.20F);
		level.playSound(
				null,
				x,
				fighter.getY(),
				z,
				SoundEvents.GENERIC_EXTINGUISH_FIRE,
				SoundSource.NEUTRAL,
				0.35F,
				1.15F);
	}

	/** Successful core damage feedback at the tower. */
	static void onCoreHit(ServerLevel level, BlockPos corePos) {
		if (level == null || corePos == null) {
			return;
		}
		double x = corePos.getX() + 0.5;
		double y = corePos.getY() + 0.85;
		double z = corePos.getZ() + 0.5;
		level.sendParticles(ParticleTypes.CRIT, x, y, z, 14, 0.40, 0.45, 0.40, 0.15);
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, x, y, z, 6, 0.30, 0.25, 0.30, 0.06);
		level.sendParticles(ParticleTypes.SMOKE, x, y, z, 5, 0.25, 0.20, 0.25, 0.01);
		level.playSound(
				null,
				corePos,
				SoundEvents.STONE_HIT,
				SoundSource.BLOCKS,
				0.85F,
				0.85F + level.random.nextFloat() * 0.15F);
		level.playSound(
				null,
				corePos,
				SoundEvents.ANVIL_PLACE,
				SoundSource.BLOCKS,
				0.18F,
				1.35F + level.random.nextFloat() * 0.10F);
	}

	private static Vec3 swingPoint(ArenaFighterEntity attacker) {
		float yaw = attacker.yBodyRot * ((float) Math.PI / 180.0F);
		double forwardX = -Math.sin(yaw) * 0.55;
		double forwardZ = Math.cos(yaw) * 0.55;
		return new Vec3(
				attacker.getX() + forwardX,
				attacker.getY() + attacker.getBbHeight() * 0.72,
				attacker.getZ() + forwardZ);
	}
}
