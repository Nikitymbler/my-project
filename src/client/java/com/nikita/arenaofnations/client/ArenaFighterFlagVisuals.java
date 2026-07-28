package com.nikita.arenaofnations.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Cached flag textures and tier-based overhead indicator sizing.
 */
public final class ArenaFighterFlagVisuals {
	public static final ResourceLocation WHITE_PIXEL =
			ArenaOfNations.id("textures/gui/white_pixel.png");

	/** Magenta fallback when a country flag PNG is missing from resources. */
	public static final ResourceLocation FALLBACK_TEXTURE =
			ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");

	private static final Map<Country, ResourceLocation> FLAG_TEXTURES = new EnumMap<>(Country.class);
	private static final AtomicBoolean LOGGED_RESOURCE_CHECK = new AtomicBoolean(false);

	static {
		for (Country country : Country.values()) {
			FLAG_TEXTURES.put(country, ArenaOfNations.id("textures/gui/flags/" + country.getId() + ".png"));
		}
	}

	/** Logical half-extents for 128×80 flag texture (world aspect 8:5). */
	public static final float FLAG_HALF_WIDTH = 16.0F;
	public static final float FLAG_HALF_HEIGHT = 10.0F;

	public static final float BASE_BILLBOARD_SCALE = 0.032F;

	public static final double SHOW_FLAG_DIST_SQR = 40.0D * 40.0D;
	public static final double SHOW_HEALTH_DIST_SQR = 18.0D * 18.0D;

	private ArenaFighterFlagVisuals() {
	}

	public static ResourceLocation flagTexture(Country country) {
		Country resolved = country != null ? country : Country.RU;
		ResourceLocation custom = FLAG_TEXTURES.get(resolved);
		if (resourceExists(custom)) {
			return custom;
		}
		return FALLBACK_TEXTURE;
	}

	public static ResourceLocation flagTexture(ArenaFighterEntity entity) {
		return flagTexture(entity.getArenaCountry());
	}

	public static boolean isUsingFallback(Country country) {
		Country resolved = country != null ? country : Country.RU;
		return !resourceExists(FLAG_TEXTURES.get(resolved));
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

	public static float indicatorY(ArenaFighterEntity entity) {
		float visualHeight = Math.max(entity.getBbHeight(), 1.8F * ArenaFighterVisuals.visualScale(entity));
		float y = visualHeight + 0.48F;
		if (entity.getArenaTier() == FighterTier.TITAN) {
			y += 0.05F;
		}
		return y;
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
				"ArenaFighterFlagVisuals: country={}, expected={}, exists={}, used={}, fallback={}, scale={}, indicatorY={}",
				country,
				expected,
				exists,
				usedTexture,
				usedTexture.equals(FALLBACK_TEXTURE),
				scale,
				indicatorY);
		minecraftResourceSizeLog(expected);
	}

	private static void minecraftResourceSizeLog(ResourceLocation id) {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) {
				return;
			}
			minecraft.getResourceManager().getResource(id).ifPresent(resource -> {
				try (var stream = resource.open()) {
					javax.imageio.ImageIO.setUseCache(false);
					var image = javax.imageio.ImageIO.read(stream);
					if (image != null) {
						ArenaOfNations.LOGGER.info(
								"ArenaFighterFlagVisuals: PNG {} size={}x{} type={}",
								id,
								image.getWidth(),
								image.getHeight(),
								image.getType());
					} else {
						ArenaOfNations.LOGGER.warn("ArenaFighterFlagVisuals: ImageIO returned null for {}", id);
					}
				} catch (Exception e) {
					ArenaOfNations.LOGGER.warn("ArenaFighterFlagVisuals: failed reading {}: {}", id, e.toString());
				}
			});
		} catch (Exception e) {
			ArenaOfNations.LOGGER.warn("ArenaFighterFlagVisuals: resource probe failed: {}", e.toString());
		}
	}
}