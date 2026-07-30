package com.nikita.arenaofnations.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * Central client-side look-up for the shared fighter skin and visual-only scale.
 * All fighters use one skin; no per-country skin variants in live render path.
 */
public final class ArenaFighterVisuals {
	/**
	 * Preferred shared warrior skin. Missing → vanilla wide Steve.
	 */
	public static final ResourceLocation SHARED_SKIN =
			ArenaOfNations.id("textures/entity/fighter/medieval_soldier.png");

	public static final String FIGHTER_ENTITY_TYPE_ID = "arena_of_nations:arena_fighter";
	public static final String RENDERER_CLASS_NAME = ArenaFighterRenderer.class.getSimpleName();
	public static final String MODEL_CLASS_NAME = ArenaFighterHumanoidModel.class.getSimpleName();

	/** Maps requested custom texture → resolved texture. */
	private static final Map<ResourceLocation, ResourceLocation> TEXTURE_RESOLUTION_CACHE =
			new ConcurrentHashMap<>();
	private static final AtomicBoolean MISSING_SKIN_LOGGED = new AtomicBoolean(false);

	private ArenaFighterVisuals() {
	}

	public static ResourceLocation texture(ArenaFighterEntity entity) {
		return sharedTexture();
	}

	/** Single skin resource used by every fighter. */
	public static ResourceLocation sharedTexture() {
		return TEXTURE_RESOLUTION_CACHE.computeIfAbsent(SHARED_SKIN, ArenaFighterVisuals::resolveTexture);
	}

	public static ResourceLocation sharedSkinResourceId() {
		return SHARED_SKIN;
	}

	public static ResourceLocation resolvedSkinResourceId() {
		return sharedTexture();
	}

	private static ResourceLocation resolveTexture(ResourceLocation custom) {
		if (!resourceExists(custom) && MISSING_SKIN_LOGGED.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.error(
					"Missing fighter skin resource: {}. Place original Mullraugh PNG at this exact id.",
					custom);
		}
		// Do not silently switch to Steve/Alex during diagnostics.
		return custom;
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
		MISSING_SKIN_LOGGED.set(false);
	}

	public static boolean sharedTextureExists() {
		return resourceExists(SHARED_SKIN);
	}

	public static String sharedTextureDimensions() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return "unknown";
		}
		try {
			Resource resource = minecraft.getResourceManager().getResource(SHARED_SKIN).orElse(null);
			if (resource == null) {
				return "missing";
			}
			try (var in = resource.open()) {
				byte[] header = in.readNBytes(24);
				if (header.length < 24) {
					return "invalid_png_header";
				}
				if (header[0] != (byte) 0x89
						|| header[1] != 0x50
						|| header[2] != 0x4E
						|| header[3] != 0x47
						|| header[4] != 0x0D
						|| header[5] != 0x0A
						|| header[6] != 0x1A
						|| header[7] != 0x0A) {
					return "invalid_png_signature";
				}
				int width = ((header[16] & 0xFF) << 24)
						| ((header[17] & 0xFF) << 16)
						| ((header[18] & 0xFF) << 8)
						| (header[19] & 0xFF);
				int height = ((header[20] & 0xFF) << 24)
						| ((header[21] & 0xFF) << 16)
						| ((header[22] & 0xFF) << 8)
						| (header[23] & 0xFF);
				if (width <= 0 || height <= 0) {
					return "invalid_png_dimensions";
				}
				return width + "x" + height;
			}
		} catch (Exception ex) {
			return "decode_error";
		}
	}

	public static boolean usingDefaultSteve() {
		return false;
	}

	public static boolean usingPlayerSkinManager() {
		return false;
	}

	public static void ensureSkinDiagnosticsLogged() {
		sharedTexture();
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
}
