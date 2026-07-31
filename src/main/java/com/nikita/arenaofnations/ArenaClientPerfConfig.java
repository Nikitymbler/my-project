package com.nikita.arenaofnations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Local client FPS settings. Never mixed with server gameplay config.
 * Safe defaults are used when the file is missing or corrupt.
 */
public final class ArenaClientPerfConfig {
	public static final String FILE_NAME = "arena_of_nations-client.properties";

	public static final int DEFAULT_RENDER_DISTANCE = 128;
	public static final int DEFAULT_LOD_MID = 32;
	public static final int DEFAULT_LOD_FAR = 64;
	public static final int DEFAULT_NAMEPLATE_DISTANCE = 24;
	public static final int DEFAULT_MAX_NAMEPLATES = 20;
	public static final boolean DEFAULT_SHADOWS = false;
	public static final boolean DEFAULT_ADAPTIVE = true;
	public static final int DEFAULT_MAX_PARTICLES = 20;
	public static final int DEFAULT_PARTICLE_DISTANCE = 48;

	private static volatile ArenaClientPerfConfig INSTANCE = defaults();

	private final int fighterRenderDistanceBlocks;
	private final int fighterLodMidDistanceBlocks;
	private final int fighterLodFarDistanceBlocks;
	private final int fighterNameplateDistanceBlocks;
	private final int maxVisibleFighterNameplates;
	private final boolean fighterShadowsEnabled;
	private final boolean adaptiveFighterRendering;
	private final int maxArenaParticlesPerTick;
	private final int arenaParticleDistanceBlocks;

	private ArenaClientPerfConfig(
			int fighterRenderDistanceBlocks,
			int fighterLodMidDistanceBlocks,
			int fighterLodFarDistanceBlocks,
			int fighterNameplateDistanceBlocks,
			int maxVisibleFighterNameplates,
			boolean fighterShadowsEnabled,
			boolean adaptiveFighterRendering,
			int maxArenaParticlesPerTick,
			int arenaParticleDistanceBlocks) {
		this.fighterRenderDistanceBlocks = fighterRenderDistanceBlocks;
		this.fighterLodMidDistanceBlocks = fighterLodMidDistanceBlocks;
		this.fighterLodFarDistanceBlocks = fighterLodFarDistanceBlocks;
		this.fighterNameplateDistanceBlocks = fighterNameplateDistanceBlocks;
		this.maxVisibleFighterNameplates = maxVisibleFighterNameplates;
		this.fighterShadowsEnabled = fighterShadowsEnabled;
		this.adaptiveFighterRendering = adaptiveFighterRendering;
		this.maxArenaParticlesPerTick = maxArenaParticlesPerTick;
		this.arenaParticleDistanceBlocks = arenaParticleDistanceBlocks;
	}

	public static ArenaClientPerfConfig get() {
		return INSTANCE;
	}

	public static ArenaClientPerfConfig defaults() {
		return sanitize(
				DEFAULT_RENDER_DISTANCE,
				DEFAULT_LOD_MID,
				DEFAULT_LOD_FAR,
				DEFAULT_NAMEPLATE_DISTANCE,
				DEFAULT_MAX_NAMEPLATES,
				DEFAULT_SHADOWS,
				DEFAULT_ADAPTIVE,
				DEFAULT_MAX_PARTICLES,
				DEFAULT_PARTICLE_DISTANCE);
	}

	public static void load() {
		INSTANCE = loadFrom(configPath());
	}

	public static void reload() {
		load();
	}

	/** Test / override path helper. */
	public static ArenaClientPerfConfig loadFrom(Path path) {
		Properties defaults = defaultProperties();
		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
				try (OutputStream out = Files.newOutputStream(tmp)) {
					defaults.store(out, "Arena of Nations client performance (local FPS only)");
				}
				try {
					Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException ignored) {
					Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
				}
				return fromProperties(defaults);
			}
			Properties properties = new Properties();
			properties.putAll(defaults);
			try (InputStream in = Files.newInputStream(path)) {
				properties.load(in);
			}
			return fromProperties(properties);
		} catch (Exception e) {
			ArenaOfNations.LOGGER.warn("Failed to load client perf config {}, using defaults", path, e);
			return defaults();
		}
	}

	public static Path configPath() {
		String override = System.getProperty("arena.client.perf.config");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static ArenaClientPerfConfig fromProperties(Properties properties) {
		int render = readInt(properties, "fighter_render_distance_blocks", DEFAULT_RENDER_DISTANCE);
		int mid = readInt(properties, "fighter_lod_mid_distance_blocks", DEFAULT_LOD_MID);
		int far = readInt(properties, "fighter_lod_far_distance_blocks", DEFAULT_LOD_FAR);
		int nameplate = readInt(properties, "fighter_nameplate_distance_blocks", DEFAULT_NAMEPLATE_DISTANCE);
		int maxPlates = readInt(properties, "max_visible_fighter_nameplates", DEFAULT_MAX_NAMEPLATES);
		boolean shadows = readBoolean(properties, "fighter_shadows_enabled", DEFAULT_SHADOWS);
		boolean adaptive = readBoolean(properties, "adaptive_fighter_rendering", DEFAULT_ADAPTIVE);
		int particles = readInt(properties, "max_arena_particles_per_tick", DEFAULT_MAX_PARTICLES);
		int particleDist = readInt(properties, "arena_particle_distance_blocks", DEFAULT_PARTICLE_DISTANCE);
		return sanitize(render, mid, far, nameplate, maxPlates, shadows, adaptive, particles, particleDist);
	}

	/**
	 * Clamps / orders distances so far ≥ mid and render ≥ far.
	 * Corrupt or out-of-range values fall back toward safe defaults.
	 */
	public static ArenaClientPerfConfig sanitize(
			int renderDistance,
			int midDistance,
			int farDistance,
			int nameplateDistance,
			int maxNameplates,
			boolean shadowsEnabled,
			boolean adaptive,
			int maxParticles,
			int particleDistance) {
		int render = clamp(renderDistance, 32, 256, DEFAULT_RENDER_DISTANCE);
		int mid = clamp(midDistance, 8, 128, DEFAULT_LOD_MID);
		int far = clamp(farDistance, 8, 256, DEFAULT_LOD_FAR);
		if (far < mid) {
			far = mid;
		}
		if (render < far) {
			render = far;
		}
		int nameplate = clamp(nameplateDistance, 0, 64, DEFAULT_NAMEPLATE_DISTANCE);
		int plates = clamp(maxNameplates, 0, 100, DEFAULT_MAX_NAMEPLATES);
		int particles = clamp(maxParticles, 0, 200, DEFAULT_MAX_PARTICLES);
		int pDist = clamp(particleDistance, 0, 128, DEFAULT_PARTICLE_DISTANCE);
		return new ArenaClientPerfConfig(
				render,
				mid,
				far,
				nameplate,
				plates,
				shadowsEnabled,
				adaptive,
				particles,
				pDist);
	}

	static void replaceForTest(ArenaClientPerfConfig config) {
		INSTANCE = config == null ? defaults() : config;
	}

	private static Properties defaultProperties() {
		Properties properties = new Properties();
		properties.setProperty("fighter_render_distance_blocks", Integer.toString(DEFAULT_RENDER_DISTANCE));
		properties.setProperty("fighter_lod_mid_distance_blocks", Integer.toString(DEFAULT_LOD_MID));
		properties.setProperty("fighter_lod_far_distance_blocks", Integer.toString(DEFAULT_LOD_FAR));
		properties.setProperty("fighter_nameplate_distance_blocks", Integer.toString(DEFAULT_NAMEPLATE_DISTANCE));
		properties.setProperty("max_visible_fighter_nameplates", Integer.toString(DEFAULT_MAX_NAMEPLATES));
		properties.setProperty("fighter_shadows_enabled", Boolean.toString(DEFAULT_SHADOWS));
		properties.setProperty("adaptive_fighter_rendering", Boolean.toString(DEFAULT_ADAPTIVE));
		properties.setProperty("max_arena_particles_per_tick", Integer.toString(DEFAULT_MAX_PARTICLES));
		properties.setProperty("arena_particle_distance_blocks", Integer.toString(DEFAULT_PARTICLE_DISTANCE));
		return properties;
	}

	private static int readInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean readBoolean(Properties properties, String key, boolean fallback) {
		String raw = properties.getProperty(key, Boolean.toString(fallback));
		if (raw == null) {
			return fallback;
		}
		String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
		if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) {
			return true;
		}
		if ("false".equals(value) || "no".equals(value) || "0".equals(value)) {
			return false;
		}
		return fallback;
	}

	private static int clamp(int value, int min, int max, int fallback) {
		if (value < min || value > max) {
			return fallback;
		}
		return value;
	}

	public int fighterRenderDistanceBlocks() {
		return fighterRenderDistanceBlocks;
	}

	public int fighterLodMidDistanceBlocks() {
		return fighterLodMidDistanceBlocks;
	}

	public int fighterLodFarDistanceBlocks() {
		return fighterLodFarDistanceBlocks;
	}

	public int fighterNameplateDistanceBlocks() {
		return fighterNameplateDistanceBlocks;
	}

	public int maxVisibleFighterNameplates() {
		return maxVisibleFighterNameplates;
	}

	public boolean fighterShadowsEnabled() {
		return fighterShadowsEnabled;
	}

	public boolean adaptiveFighterRendering() {
		return adaptiveFighterRendering;
	}

	public int maxArenaParticlesPerTick() {
		return maxArenaParticlesPerTick;
	}

	public int arenaParticleDistanceBlocks() {
		return arenaParticleDistanceBlocks;
	}

	public double renderDistanceSqr() {
		double d = fighterRenderDistanceBlocks;
		return d * d;
	}

	public double midDistanceSqr() {
		double d = fighterLodMidDistanceBlocks;
		return d * d;
	}

	public double farDistanceSqr() {
		double d = fighterLodFarDistanceBlocks;
		return d * d;
	}

	public double nameplateDistanceSqr() {
		double d = fighterNameplateDistanceBlocks;
		return d * d;
	}

	public double particleDistanceSqr() {
		double d = arenaParticleDistanceBlocks;
		return d * d;
	}
}
