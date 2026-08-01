package com.nikita.arenaofnations.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Cached flag textures and overhead indicator sizing.
 * Single render path: {@link ArenaFighterOverheadRenderer} via {@link ArenaFighterRenderer}.
 */
public final class ArenaFighterFlagVisuals {
	public static final ResourceLocation WHITE_PIXEL =
			ArenaOfNations.id("textures/gui/white_pixel.png");

	/** Magenta fallback when a country flag PNG is missing from resources. */
	public static final ResourceLocation FALLBACK_TEXTURE =
			ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");

	/** Exactly one registered world render path draws fighter overhead flags. */
	public static final int FIGHTER_FLAG_RENDER_PATH_COUNT = 1;
	public static final String FIGHTER_FLAG_RENDER_PATH =
			"ArenaFighterRenderer -> ArenaFighterOverheadRenderer";

	private static final Map<Country, ResourceLocation> FLAG_TEXTURES = new EnumMap<>(Country.class);
	private static final Map<Country, ResourceLocation> FLAG_RESOLUTION_CACHE = new ConcurrentHashMap<>();
	private static final AtomicBoolean LOGGED_RESOURCE_CHECK = new AtomicBoolean(false);
	private static final AtomicInteger LAST_HIDE_REASON = new AtomicInteger(0);

	/** Bit flags for last hide reason (diagnostics). */
	public static final int HIDE_NONE = 0;
	public static final int HIDE_DEAD = 1;
	public static final int HIDE_INVISIBLE = 2;
	public static final int HIDE_DISTANCE = 3;

	static {
		for (Country country : Country.values()) {
			// Same HD atlas as base markers — keeps US stars / hoist details consistent.
			FLAG_TEXTURES.put(country, ArenaOfNations.id("textures/gui/flags_hd/" + country.getId() + ".png"));
		}
	}

	/** Logical half-extents for 256×160 HD flag texture (world aspect 8:5). */
	public static final float FLAG_HALF_WIDTH = 16.0F;
	public static final float FLAG_HALF_HEIGHT = 10.0F;

	public static final float BASE_BILLBOARD_SCALE = 0.032F;

	/** Confirmed show distance (do not reduce). */
	public static final double SHOW_FLAG_DIST = 40.0D;
	public static final double SHOW_FLAG_DIST_SQR = SHOW_FLAG_DIST * SHOW_FLAG_DIST;
	/**
	 * Hysteresis: once visible, keep showing until slightly farther to avoid
	 * frame-to-frame flicker at the distance boundary.
	 */
	public static final double HIDE_FLAG_DIST = 42.0D;
	public static final double HIDE_FLAG_DIST_SQR = HIDE_FLAG_DIST * HIDE_FLAG_DIST;

	public static final double SHOW_HEALTH_DIST = 18.0D;
	public static final double SHOW_HEALTH_DIST_SQR = SHOW_HEALTH_DIST * SHOW_HEALTH_DIST;
	public static final double HIDE_HEALTH_DIST = 20.0D;
	public static final double HIDE_HEALTH_DIST_SQR = HIDE_HEALTH_DIST * HIDE_HEALTH_DIST;

	private static final ConcurrentHashMap<Integer, Boolean> FLAG_VISIBLE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, Boolean> HEALTH_VISIBLE = new ConcurrentHashMap<>();
	private static int pruneCounter;

	private ArenaFighterFlagVisuals() {
	}

	public static ResourceLocation flagTexture(Country country) {
		Country resolved = country != null ? country : Country.RU;
		return FLAG_RESOLUTION_CACHE.computeIfAbsent(resolved, key -> {
			ResourceLocation custom = FLAG_TEXTURES.get(key);
			if (resourceExists(custom)) {
				return custom;
			}
			return FALLBACK_TEXTURE;
		});
	}

	public static ResourceLocation flagTexture(ArenaFighterEntity entity) {
		return flagTexture(entity.getArenaCountry());
	}

	public static boolean isUsingFallback(Country country) {
		return flagTexture(country).equals(FALLBACK_TEXTURE);
	}

	public static void clearTextureCache() {
		FLAG_RESOLUTION_CACHE.clear();
		FLAG_VISIBLE.clear();
		HEALTH_VISIBLE.clear();
	}

	public static float indicatorScale(FighterTier tier) {
		FighterTier resolved = tier != null ? tier : FighterTier.SCOUT;
		return switch (resolved) {
			case SCOUT -> 0.90F;
			case WARRIOR -> 1.00F;
			case HEAVY -> 1.08F;
			case HERO -> 1.16F;
			case TITAN -> 1.28F;
		};
	}

	public static float indicatorScale(ArenaFighterEntity entity) {
		return indicatorScale(entity.getArenaTier());
	}

	public static float billboardScale(ArenaFighterEntity entity) {
		return BASE_BILLBOARD_SCALE * indicatorScale(entity);
	}

	/**
	 * Stable indicator height — uses visual scale, not fluctuating bbHeight,
	 * so the flag does not jump between frames.
	 */
	public static float indicatorY(ArenaFighterEntity entity) {
		float visualHeight = 1.8F * ArenaFighterVisuals.visualScale(entity);
		return visualHeight + 0.48F;
	}

	/**
	 * Distance hysteresis for flag visibility. Enter at 40 blocks, leave at 42.
	 */
	public static boolean shouldShowFlag(ArenaFighterEntity entity, double distSqr) {
		int id = entity.getId();
		boolean wasVisible = FLAG_VISIBLE.getOrDefault(id, false);
		boolean nowVisible;
		if (wasVisible) {
			nowVisible = distSqr <= HIDE_FLAG_DIST_SQR;
		} else {
			nowVisible = distSqr <= SHOW_FLAG_DIST_SQR;
		}
		FLAG_VISIBLE.put(id, nowVisible);
		maybePrune();
		if (!nowVisible) {
			LAST_HIDE_REASON.set(HIDE_DISTANCE);
		}
		return nowVisible;
	}

	public static boolean shouldShowHealth(ArenaFighterEntity entity, double distSqr) {
		int id = entity.getId();
		boolean wasVisible = HEALTH_VISIBLE.getOrDefault(id, false);
		boolean nowVisible;
		if (wasVisible) {
			nowVisible = distSqr <= HIDE_HEALTH_DIST_SQR;
		} else {
			nowVisible = distSqr <= SHOW_HEALTH_DIST_SQR;
		}
		HEALTH_VISIBLE.put(id, nowVisible);
		return nowVisible;
	}

	public static void recordHideReason(int reason) {
		LAST_HIDE_REASON.set(reason);
	}

	public static String lastHideReasonLabel() {
		return switch (LAST_HIDE_REASON.get()) {
			case HIDE_DEAD -> "dead_or_removed";
			case HIDE_INVISIBLE -> "invisible";
			case HIDE_DISTANCE -> "distance";
			default -> "none";
		};
	}

	private static void maybePrune() {
		pruneCounter++;
		if (pruneCounter < 200) {
			return;
		}
		pruneCounter = 0;
		if (FLAG_VISIBLE.size() > 4096) {
			FLAG_VISIBLE.clear();
			HEALTH_VISIBLE.clear();
		}
	}

	public static boolean resourceExists(ResourceLocation id) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return false;
		}
		return minecraft.getResourceManager().getResource(id).isPresent();
	}

	/**
	 * One-shot resource diagnostics after the first overhead draw.
	 */
	public static void logOnce(ArenaFighterEntity entity, ResourceLocation usedTexture, float scale, float indicatorY) {
		if (!LOGGED_RESOURCE_CHECK.compareAndSet(false, true)) {
			return;
		}
		Country country = entity.getArenaCountry();
		ResourceLocation expected = FLAG_TEXTURES.get(country != null ? country : Country.RU);
		boolean exists = resourceExists(expected);
		ArenaOfNations.LOGGER.info(
				"ArenaFighterFlagVisuals: country={}, expected={}, exists={}, used={}, fallback={}, scale={}, indicatorY={}, renderPaths={}",
				country,
				expected,
				exists,
				usedTexture,
				usedTexture.equals(FALLBACK_TEXTURE),
				scale,
				indicatorY,
				FIGHTER_FLAG_RENDER_PATH_COUNT);
	}
}
