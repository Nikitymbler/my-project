package com.nikita.arenaofnations;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Viewer team-join chat parsing. Primary formats: {@code ru}, {@code команда ru}.
 * Legacy {@code !ru} remains for compatibility only.
 */
public final class ArenaTeamJoinParser {
	private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
	private static final Pattern TEAM_WORD = Pattern.compile("^команда\\s+([a-z0-9]+)$");
	private static final Pattern LEGACY_BANG = Pattern.compile("^!([a-z0-9]+)$");

	private ArenaTeamJoinParser() {
	}

	public record Result(
			Country country,
			String normalizedComment,
			boolean legacyFormat,
			boolean matched) {
		public static Result none(String normalized) {
			return new Result(null, normalized == null ? "" : normalized, false, false);
		}
	}

	/**
	 * Trim, lower-case, collapse internal whitespace. Does not strip punctuation
	 * so ordinary sentences cannot accidentally become team codes.
	 */
	public static String normalize(String message) {
		if (message == null) {
			return "";
		}
		String trimmed = message.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return "";
		}
		return MULTI_SPACE.matcher(trimmed).replaceAll(" ");
	}

	public static Result parse(String message) {
		String normalized = normalize(message);
		if (normalized.isEmpty()) {
			return Result.none(normalized);
		}

		Matcher legacy = LEGACY_BANG.matcher(normalized);
		if (legacy.matches()) {
			Country country = Country.byId(legacy.group(1));
			if (country == null) {
				return Result.none(normalized);
			}
			return new Result(country, normalized, true, true);
		}

		Matcher teamWord = TEAM_WORD.matcher(normalized);
		if (teamWord.matches()) {
			Country country = Country.byId(teamWord.group(1));
			if (country == null) {
				return Result.none(normalized);
			}
			return new Result(country, normalized, false, true);
		}

		// Exact code/id only — whole comment must be the code.
		Country exact = Country.byId(normalized);
		if (exact != null && (normalized.equals(exact.getId()) || normalized.equals(exact.getCode().toLowerCase(Locale.ROOT)))) {
			return new Result(exact, normalized, false, true);
		}

		return Result.none(normalized);
	}
}
