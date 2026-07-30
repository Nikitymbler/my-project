package com.nikita.arenaofnations;

import java.net.URI;
import java.util.Locale;

/**
 * Validates and builds the configured public TikTok overlay URL.
 * Does not store or accept tunnel tokens.
 */
public final class ArenaOverlayPublicUrl {
	public record ValidationResult(boolean valid, String url, String reason) {
		public static ValidationResult ok(String url) {
			return new ValidationResult(true, url, "");
		}

		public static ValidationResult fail(String reason) {
			return new ValidationResult(false, "", reason);
		}
	}

	private ArenaOverlayPublicUrl() {
	}

	public static ValidationResult validate(String baseUrl, String path) {
		String base = baseUrl == null ? "" : baseUrl.trim();
		String relative = path == null ? "" : path.trim();
		if (base.isEmpty()) {
			return ValidationResult.fail("empty_base_url");
		}
		if (base.contains(" ") || relative.contains(" ")) {
			return ValidationResult.fail("whitespace");
		}
		if (relative.toLowerCase(Locale.ROOT).contains("preview=1")) {
			return ValidationResult.fail("preview_query_not_allowed");
		}
		if (!relative.startsWith("/")) {
			return ValidationResult.fail("path_must_start_with_slash");
		}
		if (relative.contains("//")) {
			return ValidationResult.fail("path_double_slash");
		}
		URI uri;
		try {
			uri = URI.create(base);
		} catch (IllegalArgumentException e) {
			return ValidationResult.fail("invalid_base_uri");
		}
		if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
			return ValidationResult.fail("https_required");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return ValidationResult.fail("missing_hostname");
		}
		String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		String combined = normalizedBase + relative;
		try {
			URI full = URI.create(combined);
			if (full.getHost() == null || full.getHost().isBlank()) {
				return ValidationResult.fail("missing_hostname");
			}
			if (!"https".equalsIgnoreCase(full.getScheme())) {
				return ValidationResult.fail("https_required");
			}
			String query = full.getRawQuery();
			if (query != null && query.toLowerCase(Locale.ROOT).contains("preview=1")) {
				return ValidationResult.fail("preview_query_not_allowed");
			}
			return ValidationResult.ok(combined);
		} catch (IllegalArgumentException e) {
			return ValidationResult.fail("invalid_combined_uri");
		}
	}

	public static String buildOrEmpty(String baseUrl, String path) {
		ValidationResult result = validate(baseUrl, path);
		return result.valid() ? result.url() : "";
	}
}
