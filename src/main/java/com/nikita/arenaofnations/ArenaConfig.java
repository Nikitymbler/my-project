package com.nikita.arenaofnations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public final class ArenaConfig {
	private static final String FILE_NAME = "arena_of_nations.properties";

	private static ArenaConfig instance = new ArenaConfig();

	private int waitingSeconds = 60;
	private int battleSeconds = 600;
	private int breakSeconds = 15;
	private int joinClosesBeforeEndSeconds = 120;
	private int maxWaitingFighters = 10;
	private int reserveWaveSize = 10;
	private int reserveWaveIntervalTicks = 40;

	private int scoutMinCoins = FighterTier.SCOUT.getGiftCost();
	private int warriorMinCoins = FighterTier.WARRIOR.getGiftCost();
	private int heavyMinCoins = FighterTier.HEAVY.getGiftCost();
	private int heroMinCoins = FighterTier.HERO.getGiftCost();
	private int titanMinCoins = FighterTier.TITAN.getGiftCost();
	private int coreMaxHealth = 200;
	private int coreRescueSeconds = 30;
	private int coreRescueHealthPercent = 50;
	private boolean hudEnabled = true;
	private int hudUpdateTicks = 10;
	private int hudViewDistance = 128;
	private boolean viewerEventsEnabled = true;
	private int viewerEventQueueLimit = 10000;
	private int viewerEventDedupSeconds = 600;
	private boolean s2eHttpEnabled = false;
	private int s2eHttpPort = 8765;
	private String s2eHttpToken = "";
	private boolean overlayEnabled = true;
	private String overlayBindAddress = "127.0.0.1";
	private int overlayPort = 8765;
	private int overlayPollMs = 250;
	private ArenaHudDisplayMode defaultHudMode = ArenaHudDisplayMode.EXTERNAL;

	private ArenaConfig() {
	}

	public static ArenaConfig get() {
		return instance;
	}

	public static void load() {
		instance.loadFromDisk();
	}

	public void reload() {
		loadFromDisk();
	}

	private void loadFromDisk() {
		Path path = configPath();
		Properties defaults = defaultProperties();

		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				try (OutputStream out = Files.newOutputStream(path)) {
					defaults.store(out, "Arena of Nations configuration");
				}
			}

			Properties properties = new Properties();
			properties.putAll(defaults);

			try (InputStream in = Files.newInputStream(path)) {
				properties.load(in);
			}

			apply(properties);
			ArenaOfNations.LOGGER.info("Loaded arena config from {}", path);
		} catch (IOException e) {
			ArenaOfNations.LOGGER.error("Failed to load arena config, using defaults", e);
			apply(defaults);
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	private static Properties defaultProperties() {
		Properties properties = new Properties();
		properties.setProperty("waiting_seconds", "60");
		properties.setProperty("battle_seconds", "600");
		properties.setProperty("break_seconds", "15");
		properties.setProperty("join_closes_before_end_seconds", "120");
		properties.setProperty("max_waiting_fighters", "10");
		properties.setProperty("reserve_wave_size", "10");
		properties.setProperty("reserve_wave_interval_ticks", "40");
		properties.setProperty("scout_min_coins", Integer.toString(FighterTier.SCOUT.getGiftCost()));
		properties.setProperty("warrior_min_coins", Integer.toString(FighterTier.WARRIOR.getGiftCost()));
		properties.setProperty("heavy_min_coins", Integer.toString(FighterTier.HEAVY.getGiftCost()));
		properties.setProperty("hero_min_coins", Integer.toString(FighterTier.HERO.getGiftCost()));
		properties.setProperty("titan_min_coins", Integer.toString(FighterTier.TITAN.getGiftCost()));
		properties.setProperty("core_max_health", "200");
		properties.setProperty("core_rescue_seconds", "30");
		properties.setProperty("core_rescue_health_percent", "50");
		// Legacy BossBar HUD config key; BossBar is off unless /arena_hud bossbar on.
		properties.setProperty("hud_enabled", "false");
		properties.setProperty("hud_update_ticks", "10");
		properties.setProperty("hud_view_distance", "128");
		properties.setProperty("viewer_events_enabled", "true");
		properties.setProperty("viewer_event_queue_limit", "10000");
		properties.setProperty("viewer_event_dedup_seconds", "600");
		properties.setProperty("s2e_http_enabled", "false");
		properties.setProperty("s2e_http_port", "8765");
		properties.setProperty("s2e_http_token", "");
		properties.setProperty("overlay_enabled", "true");
		properties.setProperty("overlay_bind_address", "127.0.0.1");
		properties.setProperty("overlay_port", "8765");
		properties.setProperty("overlay_poll_ms", "250");
		properties.setProperty("default_hud_mode", "external");
		return properties;
	}

	private void apply(Properties properties) {
		waitingSeconds = readInt(properties, "waiting_seconds", 60);
		battleSeconds = readInt(properties, "battle_seconds", 600);
		breakSeconds = readInt(properties, "break_seconds", 15);
		joinClosesBeforeEndSeconds = readInt(properties, "join_closes_before_end_seconds", 120);
		maxWaitingFighters = readInt(properties, "max_waiting_fighters", 10);
		reserveWaveSize = readInt(properties, "reserve_wave_size", 10);
		reserveWaveIntervalTicks = readInt(properties, "reserve_wave_interval_ticks", 40);
		scoutMinCoins = readInt(properties, "scout_min_coins", 1);
		warriorMinCoins = readInt(properties, "warrior_min_coins", 10);
		heavyMinCoins = readInt(properties, "heavy_min_coins", 50);
		heroMinCoins = readInt(properties, "hero_min_coins", 200);
		titanMinCoins = readInt(properties, "titan_min_coins", 1000);
		coreMaxHealth = Math.max(1, readInt(properties, "core_max_health", 200));
		coreRescueSeconds = readBoundedInt(properties, "core_rescue_seconds", 30, 1, 300);
		coreRescueHealthPercent = readBoundedInt(properties, "core_rescue_health_percent", 50, 1, 100);
		hudEnabled = readBoolean(properties, "hud_enabled", false);
		hudUpdateTicks = readBoundedInt(properties, "hud_update_ticks", 10, 1, 100);
		hudViewDistance = readBoundedInt(properties, "hud_view_distance", 128, 16, 512);
		viewerEventsEnabled = readBoolean(properties, "viewer_events_enabled", true);
		viewerEventQueueLimit = readBoundedInt(properties, "viewer_event_queue_limit", 10000, 100, 100000);
		viewerEventDedupSeconds = readBoundedInt(properties, "viewer_event_dedup_seconds", 600, 10, 3600);
		s2eHttpEnabled = readBoolean(properties, "s2e_http_enabled", false);
		s2eHttpPort = readBoundedInt(properties, "s2e_http_port", 8765, 1024, 65535);
		s2eHttpToken = readTrimmedString(properties, "s2e_http_token", "");
		overlayEnabled = readBoolean(properties, "overlay_enabled", true);
		overlayBindAddress = readTrimmedString(properties, "overlay_bind_address", "127.0.0.1");
		overlayPort = readBoundedInt(properties, "overlay_port", 8765, 1024, 65535);
		overlayPollMs = readBoundedInt(properties, "overlay_poll_ms", 250, 100, 5000);
		defaultHudMode = ArenaHudDisplayMode.parse(
				readTrimmedString(properties, "default_hud_mode", "external"),
				ArenaHudDisplayMode.EXTERNAL);
	}

	private static int readInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
		} catch (NumberFormatException e) {
			ArenaOfNations.LOGGER.warn("Invalid config value for {}, using {}", key, fallback);
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
		ArenaOfNations.LOGGER.warn("Invalid config value for {}, using {}", key, fallback);
		return fallback;
	}

	private static int readBoundedInt(Properties properties, String key, int fallback, int min, int max) {
		int value = readInt(properties, key, fallback);
		if (value < min || value > max) {
			ArenaOfNations.LOGGER.warn(
					"Config {}={} out of range {}-{}, using default {}",
					key, value, min, max, fallback);
			return fallback;
		}
		return value;
	}

	private static String readTrimmedString(Properties properties, String key, String fallback) {
		String raw = properties.getProperty(key, fallback);
		if (raw == null) {
			return fallback;
		}
		return raw.trim();
	}

	public FighterTier tierFromCoins(int coins) {
		// Simplified gameplay mode: one class for all gifts.
		return FighterTier.SCOUT;
	}

	public int getWaitingSeconds() {
		return waitingSeconds;
	}

	public int getBattleSeconds() {
		return battleSeconds;
	}

	public int getBreakSeconds() {
		return breakSeconds;
	}

	public int getJoinClosesBeforeEndSeconds() {
		return joinClosesBeforeEndSeconds;
	}

	public int getMaxWaitingFighters() {
		return maxWaitingFighters;
	}

	public int getReserveWaveSize() {
		return reserveWaveSize;
	}

	public int getReserveWaveIntervalTicks() {
		return reserveWaveIntervalTicks;
	}

	public int getScoutMinCoins() {
		return scoutMinCoins;
	}

	public int getWarriorMinCoins() {
		return warriorMinCoins;
	}

	public int getHeavyMinCoins() {
		return heavyMinCoins;
	}

	public int getHeroMinCoins() {
		return heroMinCoins;
	}

	public int getTitanMinCoins() {
		return titanMinCoins;
	}

	public int getCoreMaxHealth() {
		return coreMaxHealth;
	}

	public int getCoreRescueSeconds() {
		return coreRescueSeconds;
	}

	public int getCoreRescueHealthPercent() {
		return coreRescueHealthPercent;
	}

	public boolean isHudEnabled() {
		return hudEnabled;
	}

	public int getHudUpdateTicks() {
		return hudUpdateTicks;
	}

	public int getHudViewDistance() {
		return hudViewDistance;
	}

	public boolean isViewerEventsEnabled() {
		return viewerEventsEnabled;
	}

	public int getViewerEventQueueLimit() {
		return viewerEventQueueLimit;
	}

	public int getViewerEventDedupSeconds() {
		return viewerEventDedupSeconds;
	}

	public boolean isS2eHttpEnabled() {
		return s2eHttpEnabled;
	}

	public int getS2eHttpPort() {
		return s2eHttpPort;
	}

	/**
	 * Configured StreamToEarn HTTP token. Empty means HTTP bridge must not start.
	 * HTTP settings apply at Minecraft server start; reload does not restart the bridge.
	 */
	public String getS2eHttpToken() {
		return s2eHttpToken;
	}

	public boolean isS2eHttpTokenConfigured() {
		return s2eHttpToken != null && !s2eHttpToken.isEmpty();
	}

	public boolean isOverlayEnabled() {
		return overlayEnabled;
	}

	public String getOverlayBindAddress() {
		return overlayBindAddress;
	}

	public int getOverlayPort() {
		return overlayPort;
	}

	public int getOverlayPollMs() {
		return overlayPollMs;
	}

	public ArenaHudDisplayMode getDefaultHudMode() {
		return defaultHudMode;
	}
}
