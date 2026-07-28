package com.nikita.arenaofnations;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Validates flag PNG assets: existence, size 64×40, id match, no duplicate hashes.
 */
public final class ArenaFlagAssetValidator {
	public static final int FLAG_WIDTH = 64;
	public static final int FLAG_HEIGHT = 40;

	public record FlagCheck(
			Country country,
			ResourceLocation location,
			boolean exists,
			int width,
			int height,
			String sha256,
			String issue) {
	}

	public record ValidationResult(boolean ok, List<FlagCheck> checks, List<String> errors) {
	}

	private ArenaFlagAssetValidator() {
	}

	public static ResourceLocation flagLocation(Country country) {
		return ArenaOfNations.id("textures/gui/flags/" + country.getId() + ".png");
	}

	public static ValidationResult validate(ResourceManager resources) {
		List<FlagCheck> checks = new ArrayList<>(Country.SUPPORTED_COUNT);
		List<String> errors = new ArrayList<>();
		Map<String, Country> hashOwners = new HashMap<>();

		for (Country country : Country.ALL) {
			ResourceLocation location = flagLocation(country);
			String issue = null;
			boolean exists = false;
			int width = -1;
			int height = -1;
			String hash = null;

			try {
				Resource resource = resources.getResourceOrThrow(location);
				try (InputStream stream = resource.open()) {
					byte[] bytes = stream.readAllBytes();
					hash = sha256(bytes);
					var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
					if (image == null) {
						issue = "PNG unreadable";
					} else {
						exists = true;
						width = image.getWidth();
						height = image.getHeight();
						if (width != FLAG_WIDTH || height != FLAG_HEIGHT) {
							issue = "size " + width + "x" + height + " (expected 64x40)";
						}
					}
				}
			} catch (Exception ex) {
				issue = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			}

			if (issue == null && hash != null) {
				Country duplicate = hashOwners.put(hash, country);
				if (duplicate != null) {
					issue = "duplicate image of " + duplicate.getId();
				}
			}
			if (issue != null) {
				errors.add(country.getId() + ": " + issue);
			}

			checks.add(new FlagCheck(country, location, exists, width, height, hash, issue));
		}

		return new ValidationResult(errors.isEmpty(), checks, errors);
	}

	private static String sha256(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(data)).substring(0, 16);
		} catch (Exception ex) {
			return "error";
		}
	}

	public static String formatReport(ValidationResult result) {
		StringBuilder builder = new StringBuilder(result.ok() ? "Flag assets OK\n" : "Flag assets FAILED\n");
		for (FlagCheck check : result.checks()) {
			builder.append(String.format(
					Locale.ROOT,
					"%s → %s exists=%s size=%dx%d hash=%s%s\n",
					check.country().getId(),
					check.location(),
					check.exists(),
					check.width(),
					check.height(),
					check.sha256() == null ? "-" : check.sha256(),
					check.issue() != null ? " ISSUE:" + check.issue() : ""));
		}
		if (!result.errors().isEmpty()) {
			builder.append("Errors:\n");
			for (String error : result.errors()) {
				builder.append("- ").append(error).append('\n');
			}
		}
		return builder.toString().trim();
	}
}
