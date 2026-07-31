package com.nikita.arenaofnations;

/**
 * Pure client render decisions for arena fighters (LOD / shadows / overhead / particles).
 * No Minecraft client types — unit-testable.
 */
public final class ArenaFighterRenderDecision {
	public static final int ADAPTIVE_HEAVY_VISIBLE = 101;
	public static final int ADAPTIVE_EXTREME_VISIBLE = 251;
	public static final int MIN_ADAPTIVE_RENDER_DISTANCE = 96;
	public static final int EXTREME_FAR_START_BLOCKS = 48;

	private ArenaFighterRenderDecision() {
	}

	public record AdaptiveState(
			int effectiveMaxNameplates,
			boolean forceDisableShadows,
			boolean forceDisableNameplates,
			int effectiveMaxParticles,
			int effectiveFarStartBlocks) {
	}

	public record FrameDecision(
			ArenaFighterLodLevel lod,
			boolean renderEntity,
			boolean renderModel,
			boolean renderSpear,
			boolean renderShadow,
			boolean renderOverhead,
			boolean allowExtraParticles) {
	}

	public static AdaptiveState adaptive(ArenaClientPerfConfig config, int visibleEstimate) {
		if (config == null) {
			config = ArenaClientPerfConfig.defaults();
		}
		if (!config.adaptiveFighterRendering() || visibleEstimate < ADAPTIVE_HEAVY_VISIBLE) {
			return new AdaptiveState(
					config.maxVisibleFighterNameplates(),
					false,
					false,
					config.maxArenaParticlesPerTick(),
					config.fighterLodFarDistanceBlocks());
		}
		if (visibleEstimate < ADAPTIVE_EXTREME_VISIBLE) {
			return new AdaptiveState(
					Math.min(10, config.maxVisibleFighterNameplates()),
					true,
					false,
					Math.max(0, config.maxArenaParticlesPerTick() / 2),
					config.fighterLodFarDistanceBlocks());
		}
		int farStart = Math.min(config.fighterLodFarDistanceBlocks(), EXTREME_FAR_START_BLOCKS);
		farStart = Math.max(config.fighterLodMidDistanceBlocks(), farStart);
		return new AdaptiveState(
				0,
				true,
				true,
				Math.min(5, config.maxArenaParticlesPerTick()),
				farStart);
	}

	public static FrameDecision decide(
			double distanceSquared,
			ArenaClientPerfConfig config,
			AdaptiveState adaptive,
			boolean nameplateBudgetRemaining) {
		if (config == null) {
			config = ArenaClientPerfConfig.defaults();
		}
		if (adaptive == null) {
			adaptive = adaptive(config, 0);
		}

		int renderBlocks = config.fighterRenderDistanceBlocks();
		if (config.adaptiveFighterRendering()) {
			renderBlocks = Math.max(MIN_ADAPTIVE_RENDER_DISTANCE, renderBlocks);
		}
		double renderSqr = (double) renderBlocks * (double) renderBlocks;
		if (distanceSquared > renderSqr) {
			return new FrameDecision(
					ArenaFighterLodLevel.CULLED,
					false,
					false,
					false,
					false,
					false,
					false);
		}

		double midSqr = config.midDistanceSqr();
		double farStart = adaptive.effectiveFarStartBlocks();
		double farSqr = farStart * farStart;
		ArenaFighterLodLevel lod;
		if (distanceSquared <= midSqr) {
			lod = ArenaFighterLodLevel.NEAR;
		} else if (distanceSquared <= farSqr) {
			lod = ArenaFighterLodLevel.MID;
		} else {
			lod = ArenaFighterLodLevel.FAR;
		}

		boolean shadowsWanted = config.fighterShadowsEnabled() && !adaptive.forceDisableShadows();
		boolean renderShadow = shadowsWanted && lod == ArenaFighterLodLevel.NEAR;

		// Stable visual policy: fighter overhead flags are never LOD/budget gated.
		// Distance hysteresis for flags remains in ArenaFighterFlagVisuals (40/42 blocks).
		boolean overhead = true;

		boolean particles = lod == ArenaFighterLodLevel.NEAR;

		return new FrameDecision(
				lod,
				true,
				true,
				true,
				renderShadow,
				overhead,
				particles);
	}

	public static boolean shouldSpawnArenaParticle(
			double distanceSquared,
			ArenaClientPerfConfig config,
			AdaptiveState adaptive,
			int particlesSpawnedThisTick,
			boolean importantSingle) {
		if (config == null) {
			config = ArenaClientPerfConfig.defaults();
		}
		if (adaptive == null) {
			adaptive = adaptive(config, 0);
		}
		if (distanceSquared > config.particleDistanceSqr()) {
			return false;
		}
		int max = adaptive.effectiveMaxParticles();
		if (max <= 0) {
			return false;
		}
		if (particlesSpawnedThisTick < max) {
			return true;
		}
		// Important single effects may still show once over the soft limit.
		return importantSingle && particlesSpawnedThisTick < max + 1;
	}
}
