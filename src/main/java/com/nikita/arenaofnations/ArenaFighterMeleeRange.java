package com.nikita.arenaofnations;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Humanoid melee contact checks for arena fighters (Wolf entity, humanoid visuals).
 */
final class ArenaFighterMeleeRange {
	static final double START_REACH = 2.35D;
	static final double CONFIRMATION_REACH = 2.95D;
	static final double MAX_VERTICAL_DIFFERENCE = 2.0D;

	private ArenaFighterMeleeRange() {
	}

	static double computeStartReach(LivingEntity attacker, LivingEntity target) {
		return START_REACH;
	}

	static double computeConfirmationReach(double startReach) {
		return CONFIRMATION_REACH;
	}

	static boolean isWithinStartRange(LivingEntity attacker, LivingEntity target) {
		return passesStartGate(attacker, target) == StartGateResult.ALLOWED;
	}

	static boolean isWithinConfirmationRange(LivingEntity attacker, LivingEntity target, double ignoredStartReach) {
		if (!isValidVertical(attacker, target)) {
			return false;
		}
		return horizontalDistance(attacker, target) <= CONFIRMATION_REACH;
	}

	static StartGateResult passesStartGate(LivingEntity attacker, LivingEntity target) {
		if (!isValidVertical(attacker, target)) {
			return StartGateResult.BLOCKED_VERTICAL;
		}
		if (horizontalDistance(attacker, target) > START_REACH) {
			return StartGateResult.BLOCKED_RANGE;
		}
		if (!hasMeleeLineOfSight(attacker, target)) {
			return StartGateResult.BLOCKED_LOS;
		}
		return StartGateResult.ALLOWED;
	}

	static boolean isValidVertical(LivingEntity attacker, LivingEntity target) {
		double attackerBottom = attacker.getY();
		double targetBottom = target.getY();
		return Math.abs(attackerBottom - targetBottom) <= MAX_VERTICAL_DIFFERENCE;
	}

	static double horizontalDistance(LivingEntity attacker, LivingEntity target) {
		double dx = attacker.getX() - target.getX();
		double dz = attacker.getZ() - target.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	static double horizontalEdgeDistance(LivingEntity attacker, LivingEntity target) {
		double center = horizontalDistance(attacker, target);
		double edge = center - attacker.getBbWidth() * 0.5D - target.getBbWidth() * 0.5D;
		return Math.max(0.0D, edge);
	}

	static double verticalDifference(LivingEntity attacker, LivingEntity target) {
		return Math.abs(attacker.getY() - target.getY());
	}

	static boolean hasMeleeLineOfSight(LivingEntity attacker, LivingEntity target) {
		if (attacker instanceof Mob mob && mob.getSensing().hasLineOfSight(target)) {
			return true;
		}
		if (!(attacker.level() instanceof net.minecraft.server.level.ServerLevel)) {
			return false;
		}
		Vec3 from = new Vec3(attacker.getX(), attacker.getY() + 1.25D, attacker.getZ());
		Vec3 to = new Vec3(target.getX(), target.getY() + 1.0D, target.getZ());
		HitResult result = attacker.level().clip(new ClipContext(
				from,
				to,
				ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE,
				attacker));
		return result.getType() == HitResult.Type.MISS;
	}

	enum StartGateResult {
		ALLOWED,
		BLOCKED_RANGE,
		BLOCKED_VERTICAL,
		BLOCKED_LOS
	}
}
