package com.nikita.arenaofnations.client;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.FighterTier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Client-only particle accents by fighter tier.
 * Budget / distance gated by {@link ArenaClientPerfRuntime}; no per-tick world class scan.
 */
public final class ArenaFighterVisualEffects {
	private static final double MOVE_POS_SQR = 0.0001;
	private static final float ATTACK_ANIM_EPS = 0.01F;

	private static final int SCOUT_MOVE_INTERVAL = 6;
	private static final int TITAN_MOVE_INTERVAL = 5;
	private static final int HERO_IDLE_INTERVAL = 15;

	private static final Map<UUID, ArenaFighterEntity> TRACKED = new ConcurrentHashMap<>();
	private static final Map<UUID, Float> PREVIOUS_ATTACK_ANIM = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> PREVIOUS_SWINGING = new ConcurrentHashMap<>();

	private static final AtomicBoolean LOGGED_FIRST_WORLD_TICK = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_FIRST_FIGHTER = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_FIRST_ATTACK = new AtomicBoolean(false);
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private ArenaFighterVisualEffects() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.warn("ArenaFighterVisualEffects.register() called more than once");
			return;
		}

		ClientTickEvents.END_CLIENT_TICK.register(ArenaFighterVisualEffects::onEndClientTick);
		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ArenaFighterEntity fighter) {
				TRACKED.put(fighter.getUUID(), fighter);
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ArenaFighterEntity) {
				forget(entity.getUUID());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());

		ArenaOfNations.LOGGER.info("ArenaFighterVisualEffects: client tick handler registered");
	}

	public static void clear() {
		TRACKED.clear();
		PREVIOUS_ATTACK_ANIM.clear();
		PREVIOUS_SWINGING.clear();
		LOGGED_FIRST_WORLD_TICK.set(false);
		LOGGED_FIRST_FIGHTER.set(false);
		LOGGED_FIRST_ATTACK.set(false);
	}

	private static void forget(UUID id) {
		TRACKED.remove(id);
		PREVIOUS_ATTACK_ANIM.remove(id);
		PREVIOUS_SWINGING.remove(id);
	}

	private static void onEndClientTick(Minecraft minecraft) {
		if (minecraft.isPaused()) {
			return;
		}

		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			clear();
			return;
		}

		if (LOGGED_FIRST_WORLD_TICK.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info("ArenaFighterVisualEffects: first client tick with loaded world");
		}

		if (TRACKED.isEmpty()) {
			return;
		}
		if (ArenaClientPerfRuntime.particlesBudgetRemaining() <= 0
				&& ArenaClientPerfRuntime.config().maxArenaParticlesPerTick() <= 0) {
			return;
		}

		double particleSqr = ArenaClientPerfRuntime.config().particleDistanceSqr();
		double px = player.getX();
		double py = player.getY();
		double pz = player.getZ();

		if (!TRACKED.isEmpty() && LOGGED_FIRST_FIGHTER.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info(
					"ArenaFighterVisualEffects: first ArenaFighterEntity tracked (count={})",
					TRACKED.size());
		}

		Iterator<Map.Entry<UUID, ArenaFighterEntity>> it = TRACKED.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, ArenaFighterEntity> entry = it.next();
			ArenaFighterEntity fighter = entry.getValue();
			if (fighter == null || !fighter.isAlive() || fighter.isRemoved() || fighter.level() != level) {
				it.remove();
				PREVIOUS_ATTACK_ANIM.remove(entry.getKey());
				PREVIOUS_SWINGING.remove(entry.getKey());
				continue;
			}
			double dx = fighter.getX() - px;
			double dy = fighter.getY() - py;
			double dz = fighter.getZ() - pz;
			double distSqr = dx * dx + dy * dy + dz * dz;
			if (distSqr > particleSqr) {
				continue;
			}
			tickFighter(fighter, level, distSqr);
			if (ArenaClientPerfRuntime.particlesBudgetRemaining() <= 0) {
				break;
			}
		}
	}

	private static void tickFighter(ArenaFighterEntity fighter, ClientLevel level, double distSqr) {
		FighterTier tier = fighter.getArenaTier();
		RandomSource random = level.random;
		UUID id = fighter.getUUID();
		boolean movingOnGround = isMovingOnGround(fighter);
		boolean attackStarted = detectAttackStart(fighter, id);
		int scheduleOffset = Math.floorMod(id.hashCode(), 32);

		switch (tier) {
			case SCOUT -> {
				if (movingOnGround && due(fighter.tickCount, scheduleOffset, SCOUT_MOVE_INTERVAL)) {
					spawnFootParticles(level, fighter, random, ParticleTypes.CLOUD, 2, distSqr, false);
				}
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 8, distSqr, true);
					spawnDamageCueTowardTarget(level, fighter, random, distSqr);
				}
			}
			case WARRIOR -> {
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 5, distSqr, true);
				}
			}
			case HEAVY -> {
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 6, distSqr, true);
					spawnSmoke(level, fighter, random, 2, distSqr, false);
				}
			}
			case HERO -> {
				if (due(fighter.tickCount, scheduleOffset, HERO_IDLE_INTERVAL)) {
					spawnEnchantedIdle(level, fighter, random, 2, distSqr, false);
				}
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 6, distSqr, true);
				}
			}
			case TITAN -> {
				if (movingOnGround && due(fighter.tickCount, scheduleOffset, TITAN_MOVE_INTERVAL)) {
					spawnFootParticles(level, fighter, random, ParticleTypes.POOF, 3, distSqr, false);
				}
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 8, distSqr, true);
				}
			}
		}
	}

	/**
	 * Prefer real swing / attackAnim (synced after ArenaFighterEntity.doHurtTarget swings).
	 * Avoids per-tick hasLineOfSight scans used previously for hurt-time fallback.
	 */
	private static boolean detectAttackStart(ArenaFighterEntity fighter, UUID id) {
		float attackAnim = fighter.getAttackAnim(0.0F);
		float prevAnim = PREVIOUS_ATTACK_ANIM.getOrDefault(id, 0.0F);
		boolean wasSwinging = PREVIOUS_SWINGING.getOrDefault(id, false);
		boolean swinging = fighter.swinging;

		boolean started = (!wasSwinging && swinging)
				|| (prevAnim <= ATTACK_ANIM_EPS && attackAnim > ATTACK_ANIM_EPS);

		PREVIOUS_ATTACK_ANIM.put(id, attackAnim);
		PREVIOUS_SWINGING.put(id, swinging);

		if (started && LOGGED_FIRST_ATTACK.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info(
					"ArenaFighterVisualEffects: first attack start detected (tier={}, swinging={}, attackAnim={})",
					fighter.getArenaTier().getId(),
					swinging,
					attackAnim);
		}

		return started;
	}

	private static boolean isMovingOnGround(ArenaFighterEntity fighter) {
		if (!fighter.onGround()) {
			return false;
		}
		double dx = fighter.getX() - fighter.xo;
		double dz = fighter.getZ() - fighter.zo;
		return dx * dx + dz * dz > MOVE_POS_SQR;
	}

	private static boolean due(int tickCount, int scheduleOffset, int interval) {
		return Math.floorMod(tickCount + scheduleOffset, interval) == 0;
	}

	private static void spawnFootParticles(
			ClientLevel level,
			ArenaFighterEntity fighter,
			RandomSource random,
			SimpleParticleType type,
			int count,
			double distSqr,
			boolean important) {
		int remaining = Math.min(count, ArenaClientPerfRuntime.particlesBudgetRemaining());
		for (int i = 0; i < remaining; i++) {
			if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, important)) {
				return;
			}
			float yaw = fighter.yBodyRot * ((float) Math.PI / 180.0F);
			double sideX = -Mth.cos(yaw) * 0.22;
			double sideZ = -Mth.sin(yaw) * 0.22;
			double side = (i % 2 == 0) ? 1.0 : -1.0;
			double x = fighter.getX() + side * sideX + (random.nextDouble() - 0.5) * 0.35;
			double y = fighter.getY() + 0.08 + random.nextDouble() * 0.06;
			double z = fighter.getZ() + side * sideZ + (random.nextDouble() - 0.5) * 0.35;
			level.addParticle(
					type,
					x,
					y,
					z,
					(random.nextDouble() - 0.5) * 0.04,
					0.04 + random.nextDouble() * 0.06,
					(random.nextDouble() - 0.5) * 0.04);
		}
	}

	private static void spawnCritBurst(
			ClientLevel level,
			ArenaFighterEntity fighter,
			RandomSource random,
			int count,
			double distSqr,
			boolean important) {
		int remaining = Math.min(count, ArenaClientPerfRuntime.particlesBudgetRemaining() + (important ? 1 : 0));
		double baseY = fighter.getY() + fighter.getBbHeight() * 0.7;
		for (int i = 0; i < remaining; i++) {
			if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, important && i == 0)) {
				return;
			}
			double x = fighter.getX() + (random.nextDouble() - 0.5) * 0.7;
			double y = baseY + (random.nextDouble() - 0.5) * 0.35;
			double z = fighter.getZ() + (random.nextDouble() - 0.5) * 0.7;
			level.addParticle(
					ParticleTypes.CRIT,
					x,
					y,
					z,
					(random.nextDouble() - 0.5) * 0.25,
					0.08 + random.nextDouble() * 0.18,
					(random.nextDouble() - 0.5) * 0.25);
		}
	}

	private static void spawnDamageCueTowardTarget(
			ClientLevel level,
			ArenaFighterEntity fighter,
			RandomSource random,
			double distSqr) {
		if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, true)) {
			return;
		}
		LivingEntity target = fighter.getTarget();
		double x;
		double y;
		double z;
		if (target != null && target.isAlive()) {
			x = Mth.lerp(0.55, fighter.getX(), target.getX());
			y = Mth.lerp(0.55, fighter.getY() + fighter.getBbHeight() * 0.65, target.getY() + target.getBbHeight() * 0.65);
			z = Mth.lerp(0.55, fighter.getZ(), target.getZ());
		} else {
			x = fighter.getX();
			y = fighter.getY() + fighter.getBbHeight() * 0.7;
			z = fighter.getZ();
		}
		level.addParticle(ParticleTypes.SWEEP_ATTACK, x, y, z, 0.0, 0.0, 0.0);
		int extra = Math.min(4, ArenaClientPerfRuntime.particlesBudgetRemaining());
		for (int i = 0; i < extra; i++) {
			if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, false)) {
				return;
			}
			level.addParticle(
					ParticleTypes.DAMAGE_INDICATOR,
					x + (random.nextDouble() - 0.5) * 0.35,
					y + (random.nextDouble() - 0.5) * 0.25,
					z + (random.nextDouble() - 0.5) * 0.35,
					(random.nextDouble() - 0.5) * 0.08,
					0.05 + random.nextDouble() * 0.08,
					(random.nextDouble() - 0.5) * 0.08);
		}
	}

	private static void spawnSmoke(
			ClientLevel level,
			ArenaFighterEntity fighter,
			RandomSource random,
			int count,
			double distSqr,
			boolean important) {
		int remaining = Math.min(count, ArenaClientPerfRuntime.particlesBudgetRemaining());
		for (int i = 0; i < remaining; i++) {
			if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, important)) {
				return;
			}
			double x = fighter.getX() + (random.nextDouble() - 0.5) * 0.7;
			double y = fighter.getY() + fighter.getBbHeight() * (0.45 + random.nextDouble() * 0.25);
			double z = fighter.getZ() + (random.nextDouble() - 0.5) * 0.7;
			level.addParticle(
					ParticleTypes.SMOKE,
					x,
					y,
					z,
					(random.nextDouble() - 0.5) * 0.03,
					0.04 + random.nextDouble() * 0.05,
					(random.nextDouble() - 0.5) * 0.03);
		}
	}

	private static void spawnEnchantedIdle(
			ClientLevel level,
			ArenaFighterEntity fighter,
			RandomSource random,
			int count,
			double distSqr,
			boolean important) {
		int remaining = Math.min(count, ArenaClientPerfRuntime.particlesBudgetRemaining());
		for (int i = 0; i < remaining; i++) {
			if (!ArenaClientPerfRuntime.tryConsumeParticle(distSqr, important)) {
				return;
			}
			double x = fighter.getX() + (random.nextDouble() - 0.5) * 0.7;
			double y = fighter.getY() + fighter.getBbHeight() * (0.35 + random.nextDouble() * 0.5);
			double z = fighter.getZ() + (random.nextDouble() - 0.5) * 0.7;
			level.addParticle(
					ParticleTypes.ENCHANTED_HIT,
					x,
					y,
					z,
					(random.nextDouble() - 0.5) * 0.2,
					0.05 + random.nextDouble() * 0.15,
					(random.nextDouble() - 0.5) * 0.2);
		}
	}
}
