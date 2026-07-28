package com.nikita.arenaofnations.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Central client-side look-up for arena fighter skins and visual-only scale.
 * Texture file names intentionally use skin aliases ({@code elite}/{@code champion})
 * while gameplay tiers remain {@link FighterTier#HEAVY}/{@link FighterTier#HERO}.
 *
 * <p>Temporary fallback: when a country/tier PNG is missing, the built-in wide Steve
 * skin is used. As soon as the custom PNG is added under
 * {@code textures/entity/fighter/<country>/<tier>.png}, the fallback stops
 * automatically (after a resource reload / cache clear).
 */
public final class ArenaFighterVisuals {
	/**
	 * Built-in Minecraft wide (Steve) skin — temporary placeholder until custom
	 * fighter PNGs exist. Not slim/Alex.
	 */
	private static final ResourceLocation FALLBACK_WIDE_STEVE =
			ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

	/** Maps requested custom texture → resolved texture (custom or Steve fallback). */
	private static final Map<ResourceLocation, ResourceLocation> TEXTURE_RESOLUTION_CACHE =
			new ConcurrentHashMap<>();

	private ArenaFighterVisuals() {
	}

	public static ResourceLocation texture(ArenaFighterEntity entity) {
		return texture(entity.getArenaCountry(), entity.getArenaTier());
	}

	public static ResourceLocation texture(Country country, FighterTier tier) {
		ResourceLocation custom = customTexture(country, tier);
		return TEXTURE_RESOLUTION_CACHE.computeIfAbsent(custom, ArenaFighterVisuals::resolveTexture);
	}

	/**
	 * Expected custom skin path (may not exist yet):
	 * {@code textures/entity/fighter/<country>/<tier>.png}
	 */
	public static ResourceLocation customTexture(Country country, FighterTier tier) {
		Country resolvedCountry = country != null ? country : Country.RU;
		FighterTier resolvedTier = tier != null ? tier : FighterTier.SCOUT;
		return ArenaOfNations.id(
				"textures/entity/fighter/"
						+ resolvedCountry.getId()
						+ "/"
						+ skinFileName(resolvedTier)
						+ ".png");
	}

	private static ResourceLocation resolveTexture(ResourceLocation custom) {
		if (resourceExists(custom)) {
			return custom;
		}
		// Temporary: missing custom PNG → vanilla wide Steve until skins are added.
		return FALLBACK_WIDE_STEVE;
	}

	private static boolean resourceExists(ResourceLocation id) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return false;
		}
		return minecraft.getResourceManager().getResource(id).isPresent();
	}

	/** Clears resolution cache (e.g. after resource pack reload). */
	public static void clearTextureCache() {
		TEXTURE_RESOLUTION_CACHE.clear();
	}

	/**
	 * Visual-only scale applied in the renderer PoseStack.
	 * Does not change hitbox, attributes, or server combat values.
	 */
	public static float visualScale(ArenaFighterEntity entity) {
		return visualScale(entity.getArenaTier());
	}

	public static float visualScale(FighterTier tier) {
		FighterTier resolved = tier != null ? tier : FighterTier.SCOUT;
		return switch (resolved) {
			case SCOUT -> 0.90F;
			case WARRIOR -> 1.00F;
			case HEAVY -> 1.10F;
			case HERO -> 1.22F;
			case TITAN -> 1.40F;
		};
	}

	/** Skin atlas file stem under {@code textures/entity/fighter/<country>/}. */
	public static String skinFileName(FighterTier tier) {
		// Single-class live mode: one skin file per country (warrior.png).
		return "warrior";
	}
}
