package com.nikita.arenaofnations.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.nikita.arenaofnations.ArenaClientPerfConfig;
import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaFighterLodLevel;
import com.nikita.arenaofnations.ArenaFighterRenderDecision;
import com.nikita.arenaofnations.Country;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;

/**
 * Client-only runtime for fighter FPS: country cache, LOD counters, adaptive estimate, particle budget.
 */
public final class ArenaClientPerfRuntime {
	private static final int ADAPTIVE_SAMPLE_TICKS = 20;

	private static final ConcurrentHashMap<Integer, CachedCountry> COUNTRY_BY_ENTITY = new ConcurrentHashMap<>();

	private static volatile ArenaFighterRenderDecision.AdaptiveState adaptive =
			ArenaFighterRenderDecision.adaptive(ArenaClientPerfConfig.defaults(), 0);

	private static final AtomicInteger frameNear = new AtomicInteger();
	private static final AtomicInteger frameMid = new AtomicInteger();
	private static final AtomicInteger frameFar = new AtomicInteger();
	private static final AtomicInteger frameDistanceCulled = new AtomicInteger();
	private static final AtomicInteger frameFrustumCulled = new AtomicInteger();
	private static final AtomicInteger frameNameplates = new AtomicInteger();
	private static final AtomicInteger frameVisible = new AtomicInteger();

	private static volatile int lastNear;
	private static volatile int lastMid;
	private static volatile int lastFar;
	private static volatile int lastDistanceCulled;
	private static volatile int lastFrustumCulled;
	private static volatile int lastNameplates;
	private static volatile int visibleEstimate;
	private static volatile int loadedArenaFighters;
	private static volatile int particlesThisTick;
	private static volatile int lastParticlesThisTick;

	private static int sampleTickCounter;
	private static int sampleVisibleAccum;
	private static int sampleFrames;
	private static boolean registered;

	private ArenaClientPerfRuntime() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ArenaClientPerfConfig.load();
		adaptive = ArenaFighterRenderDecision.adaptive(ArenaClientPerfConfig.get(), 0);

		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ArenaFighterEntity fighter) {
				rememberCountry(fighter);
				loadedArenaFighters++;
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ArenaFighterEntity) {
				COUNTRY_BY_ENTITY.remove(entity.getId());
				loadedArenaFighters = Math.max(0, loadedArenaFighters - 1);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientTickEvents.END_CLIENT_TICK.register(ArenaClientPerfRuntime::onClientTick);
	}

	public static void clear() {
		COUNTRY_BY_ENTITY.clear();
		loadedArenaFighters = 0;
		visibleEstimate = 0;
		particlesThisTick = 0;
		lastParticlesThisTick = 0;
		adaptive = ArenaFighterRenderDecision.adaptive(ArenaClientPerfConfig.get(), 0);
		resetFrameCounters();
	}

	public static void onConfigReloaded() {
		adaptive = ArenaFighterRenderDecision.adaptive(ArenaClientPerfConfig.get(), visibleEstimate);
	}

	private static void onClientTick(Minecraft minecraft) {
		lastParticlesThisTick = particlesThisTick;
		particlesThisTick = 0;

		lastNear = frameNear.getAndSet(0);
		lastMid = frameMid.getAndSet(0);
		lastFar = frameFar.getAndSet(0);
		lastDistanceCulled = frameDistanceCulled.getAndSet(0);
		lastFrustumCulled = frameFrustumCulled.getAndSet(0);
		lastNameplates = frameNameplates.getAndSet(0);
		int visibleThisFrameWindow = frameVisible.getAndSet(0);

		sampleVisibleAccum += visibleThisFrameWindow;
		sampleFrames++;
		sampleTickCounter++;
		if (sampleTickCounter >= ADAPTIVE_SAMPLE_TICKS) {
			visibleEstimate = sampleFrames <= 0 ? 0 : sampleVisibleAccum / sampleFrames;
			sampleTickCounter = 0;
			sampleVisibleAccum = 0;
			sampleFrames = 0;
			adaptive = ArenaFighterRenderDecision.adaptive(ArenaClientPerfConfig.get(), visibleEstimate);
			frameNameplates.set(0);
		}
	}

	private static void resetFrameCounters() {
		frameNear.set(0);
		frameMid.set(0);
		frameFar.set(0);
		frameDistanceCulled.set(0);
		frameFrustumCulled.set(0);
		frameNameplates.set(0);
		frameVisible.set(0);
	}

	public static Country countryOf(ArenaFighterEntity entity) {
		if (entity == null) {
			return Country.RU;
		}
		int id = entity.getId();
		String synced = entity.getSyncedCountryId();
		CachedCountry cached = COUNTRY_BY_ENTITY.get(id);
		if (cached != null && synced != null && synced.equals(cached.syncedId)) {
			return cached.country;
		}
		return rememberCountry(entity);
	}

	public static Country rememberCountry(ArenaFighterEntity entity) {
		String synced = entity.getSyncedCountryId();
		Country country = Country.byId(synced);
		if (country == null) {
			country = entity.getArenaCountry();
		}
		COUNTRY_BY_ENTITY.put(entity.getId(), new CachedCountry(synced == null ? "" : synced, country));
		return country;
	}

	public static ArenaClientPerfConfig config() {
		return ArenaClientPerfConfig.get();
	}

	public static ArenaFighterRenderDecision.AdaptiveState adaptive() {
		return adaptive;
	}

	public static ArenaFighterRenderDecision.FrameDecision decide(double distanceSquared) {
		boolean budget = frameNameplates.get() < Math.max(0, adaptive.effectiveMaxNameplates());
		ArenaFighterRenderDecision.FrameDecision decision = ArenaFighterRenderDecision.decide(
				distanceSquared,
				config(),
				adaptive,
				budget);
		if (decision.renderEntity()) {
			frameVisible.incrementAndGet();
			switch (decision.lod()) {
				case NEAR -> frameNear.incrementAndGet();
				case MID -> frameMid.incrementAndGet();
				case FAR -> frameFar.incrementAndGet();
				default -> {
				}
			}
			if (decision.renderOverhead()) {
				frameNameplates.incrementAndGet();
			}
		}
		return decision;
	}

	public static void recordDistanceCulled() {
		frameDistanceCulled.incrementAndGet();
	}

	public static void recordFrustumCulled() {
		frameFrustumCulled.incrementAndGet();
	}

	public static boolean tryConsumeParticle(double distanceSquared, boolean importantSingle) {
		boolean ok = ArenaFighterRenderDecision.shouldSpawnArenaParticle(
				distanceSquared,
				config(),
				adaptive,
				particlesThisTick,
				importantSingle);
		if (ok) {
			particlesThisTick++;
		}
		return ok;
	}

	public static int particlesBudgetRemaining() {
		return Math.max(0, adaptive.effectiveMaxParticles() - particlesThisTick);
	}

	public static String statusReport() {
		ArenaClientPerfConfig cfg = config();
		return "Arena Client Performance\n"
				+ "fighterRenderDistance=" + cfg.fighterRenderDistanceBlocks() + '\n'
				+ "lodMidDistance=" + cfg.fighterLodMidDistanceBlocks() + '\n'
				+ "lodFarDistance=" + cfg.fighterLodFarDistanceBlocks() + '\n'
				+ "fighterShadows=" + cfg.fighterShadowsEnabled() + '\n'
				+ "adaptiveRendering=" + cfg.adaptiveFighterRendering() + '\n'
				+ "loadedArenaFighters=" + loadedArenaFighters + '\n'
				+ "visibleArenaFightersEstimate=" + visibleEstimate + '\n'
				+ "nearRendered=" + lastNear + '\n'
				+ "midRendered=" + lastMid + '\n'
				+ "farRendered=" + lastFar + '\n'
				+ "distanceCulled=" + lastDistanceCulled + '\n'
				+ "frustumCulled=" + lastFrustumCulled + '\n'
				+ "fighterNameplatesRendered=" + lastNameplates + '\n'
				+ "arenaParticlesThisTick=" + lastParticlesThisTick;
	}

	public static int visibleEstimate() {
		return visibleEstimate;
	}

	public static int loadedArenaFighters() {
		return loadedArenaFighters;
	}

	private record CachedCountry(String syncedId, Country country) {
	}
}
