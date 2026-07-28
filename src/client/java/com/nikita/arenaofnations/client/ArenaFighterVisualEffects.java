package com.nikita.arenaofnations.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.phys.AABB;

/**
 * Client-only particle accents by fighter tier.
 */
public final class ArenaFighterVisualEffects {
	private static final double SEARCH_RADIUS = 96.0;
	private static final double MOVE_POS_SQR = 0.0001;
	private static final float ATTACK_ANIM_EPS = 0.01F;

	private static final int SCOUT_MOVE_INTERVAL = 6;
	private static final int TITAN_MOVE_INTERVAL = 5;
	private static final int HERO_IDLE_INTERVAL = 15;

	private static final Map<UUID, Float> PREVIOUS_ATTACK_ANIM = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> PREVIOUS_SWINGING = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> PREVIOUS_TARGET_HURT = new ConcurrentHashMap<>();

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
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ArenaFighterEntity) {
				forget(entity.getUUID());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());

		ArenaOfNations.LOGGER.info("ArenaFighterVisualEffects: client tick handler registered");
	}

	public static void clear() {
		PREVIOUS_ATTACK_ANIM.clear();
		PREVIOUS_SWINGING.clear();
		PREVIOUS_TARGET_HURT.clear();
		LOGGED_FIRST_WORLD_TICK.set(false);
		LOGGED_FIRST_FIGHTER.set(false);
		LOGGED_FIRST_ATTACK.set(false);
	}

	private static void forget(UUID id) {
		PREVIOUS_ATTACK_ANIM.remove(id);
		PREVIOUS_SWINGING.remove(id);
		PREVIOUS_TARGET_HURT.remove(id);
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

		AABB searchBox = player.getBoundingBox().inflate(SEARCH_RADIUS);
		var nearby = level.getEntitiesOfClass(ArenaFighterEntity.class, searchBox);

		if (!nearby.isEmpty() && LOGGED_FIRST_FIGHTER.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info(
					"ArenaFighterVisualEffects: first ArenaFighterEntity found (count={})",
					nearby.size());
		}

		if (player.tickCount % 100 == 0 && !nearby.isEmpty()) {
			ArenaOfNations.LOGGER.info(
					"ArenaFighterVisualEffects: nearby fighters in {} blocks: {}",
					(int) SEARCH_RADIUS,
					nearby.size());
		}

		Set<UUID> seen = new HashSet<>(Math.max(16, nearby.size() * 2));
		for (ArenaFighterEntity fighter : nearby) {
			if (!fighter.isAlive() || fighter.isRemoved()) {
				continue;
			}
			seen.add(fighter.getUUID());
			tickFighter(fighter, level);
		}

		Iterator<UUID> it = PREVIOUS_ATTACK_ANIM.keySet().iterator();
		while (it.hasNext()) {
			UUID id = it.next();
			if (!seen.contains(id)) {
				it.remove();
				PREVIOUS_SWINGING.remove(id);
				PREVIOUS_TARGET_HURT.remove(id);
			}
		}
	}

	private static void tickFighter(ArenaFighterEntity fighter, ClientLevel level) {
		FighterTier tier = fighter.getArenaTier();
		RandomSource random = level.random;
		UUID id = fighter.getUUID();
		boolean movingOnGround = isMovingOnGround(fighter);
		boolean attackStarted = detectAttackStart(fighter, id);
		int scheduleOffset = Math.floorMod(id.hashCode(), 32);

		switch (tier) {
			case SCOUT -> {
				if (movingOnGround && due(fighter.tickCount, scheduleOffset, SCOUT_MOVE_INTERVAL)) {
					spawnFootParticles(level, fighter, random, ParticleTypes.CLOUD, 2);
				}
			}
			case WARRIOR -> {
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 5);
				}
			}
			case HEAVY -> {
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 6);
					spawnSmoke(level, fighter, random, 2);
				}
			}
			case HERO -> {
				if (due(fighter.tickCount, scheduleOffset, HERO_IDLE_INTERVAL)) {
					spawnEnchantedIdle(level, fighter, random, 2);
				}
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 6);
				}
			}
			case TITAN -> {
				if (movingOnGround && due(fighter.tickCount, scheduleOffset, TITAN_MOVE_INTERVAL)) {
					spawnFootParticles(level, fighter, random, ParticleTypes.POOF, 3);
				}
				if (attackStarted) {
					spawnCritBurst(level, fighter, random, 8);
				}
			}
		}
	}

	/**
	 * Prefer real swing / attackAnim (synced after ArenaFighterEntity.doHurtTarget swings).
	 * Fallback: target hurtTime rising while this fighter is in melee range.
	 */
	private static boolean detectAttackStart(ArenaFighterEntity fighter, UUID id) {
		float attackAnim = fighter.getAttackAnim(0.0F);
		float prevAnim = PREVIOUS_ATTACK_ANIM.getOrDefault(id, 0.0F);
		boolean wasSwinging = PREVIOUS_SWINGING.getOrDefault(id, false);
		boolean swinging = fighter.swinging;

		boolean started = (!wasSwinging && swinging)
				|| (prevAnim <= ATTACK_ANIM_EPS && attackAnim > ATTACK_ANIM_EPS);

		LivingEntity target = fighter.getTarget();
		int targetHurt = target != null ? target.hurtTime : 0;
		int prevHurt = PREVIOUS_TARGET_HURT.getOrDefault(id, 0);
		if (!started && target != null && prevHurt <= 0 && targetHurt > 0) {
			if (fighter.distanceToSqr(target) <= 12.25 && fighter.hasLineOfSight(target)) {
				started = true;
			}
		}

		PREVIOUS_ATTACK_ANIM.put(id, attackAnim);
		PREVIOUS_SWINGING.put(id, swinging);
		PREVIOUS_TARGET_HURT.put(id, targetHurt);

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
		double dx = fighter.getX() - fighter.xOld;
		double dz = fighter.getZ() - fighter.zOld;
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
			int count) {
		float yaw = fighter.yBodyRot * ((float) Math.PI / 180.0F);
		double sideX = -Mth.cos(yaw) * 0.22;
		double sideZ = -Mth.sin(yaw) * 0.22;
		for (int i = 0; i < count; i++) {
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

	private static void spawnCritBurst(ClientLevel level, ArenaFighterEntity fighter, RandomSource random, int count) {
		double baseY = fighter.getY() + fighter.getBbHeight() * 0.7;
		for (int i = 0; i < count; i++) {
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

	private static void spawnSmoke(ClientLevel level, ArenaFighterEntity fighter, RandomSource random, int count) {
		for (int i = 0; i < count; i++) {
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

	private static void spawnEnchantedIdle(ClientLevel level, ArenaFighterEntity fighter, RandomSource random, int count) {
		for (int i = 0; i < count; i++) {
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
