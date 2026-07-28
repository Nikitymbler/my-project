package com.nikita.arenaofnations.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * HD base-marker flag textures (256×160) derived from overlay SVG sources.
 */
public final class ArenaBaseFlagTextures {
	public static final ResourceLocation WHITE_PIXEL =
			ArenaOfNations.id("textures/gui/white_pixel.png");
	public static final ResourceLocation FALLBACK =
			ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");

	private static final Map<Country, ResourceLocation> TEXTURES = new EnumMap<>(Country.class);

	static {
		for (Country country : Country.values()) {
			TEXTURES.put(country, ArenaOfNations.id("textures/gui/flags_hd/" + country.getId() + ".png"));
		}
	}

	private ArenaBaseFlagTextures() {
	}

	public static ResourceLocation texture(Country country) {
		Country resolved = country != null ? country : Country.RU;
		ResourceLocation id = TEXTURES.get(resolved);
		return resourceExists(id) ? id : FALLBACK;
	}

	public static boolean isMissing(Country country) {
		Country resolved = country != null ? country : Country.RU;
		return !resourceExists(TEXTURES.get(resolved));
	}

	public static int countMissing() {
		AtomicInteger missing = new AtomicInteger();
		for (Country country : Country.values()) {
			if (isMissing(country)) {
				missing.incrementAndGet();
			}
		}
		return missing.get();
	}

	public static boolean resourceExists(ResourceLocation id) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || id == null) {
			return false;
		}
		return minecraft.getResourceManager().getResource(id).isPresent();
	}
}
