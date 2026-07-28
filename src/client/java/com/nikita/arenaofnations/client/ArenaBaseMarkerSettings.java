package com.nikita.arenaofnations.client;

import java.util.List;
import java.util.Locale;

/**
 * Client toggle and diagnostics for world-space base markers.
 */
public final class ArenaBaseMarkerSettings {
	public static final double MAX_RENDER_DISTANCE = 120.0D;
	public static final double FADE_START_DISTANCE = 100.0D;

	private static boolean enabled = true;
	private static int lastActiveMarkers;
	private static int lastRenderedMarkers;
	private static List<MarkerDiag> lastDiags = List.of();

	private ArenaBaseMarkerSettings() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static void recordFrame(int active, int rendered, List<MarkerDiag> diags) {
		lastActiveMarkers = active;
		lastRenderedMarkers = rendered;
		lastDiags = diags == null ? List.of() : List.copyOf(diags);
	}

	public static int lastActiveMarkers() {
		return lastActiveMarkers;
	}

	public static int lastRenderedMarkers() {
		return lastRenderedMarkers;
	}

	public static String statusReport(int clientStateEntries) {
		StringBuilder builder = new StringBuilder();
		builder.append("Base markers:\n")
				.append("enabled=").append(enabled).append('\n')
				.append("active_markers=").append(lastActiveMarkers).append('\n')
				.append("rendered_markers=").append(lastRenderedMarkers).append('\n')
				.append("client_state_entries=").append(clientStateEntries).append('\n')
				.append("missing_textures=").append(ArenaBaseFlagTextures.countMissing()).append('\n')
				.append("max_render_distance=").append((int) MAX_RENDER_DISTANCE).append('\n')
				.append("first5:");
		if (lastDiags.isEmpty()) {
			builder.append(" (none)");
		} else {
			for (MarkerDiag diag : lastDiags) {
				builder.append('\n').append("  ").append(diag.format());
			}
		}
		return builder.toString();
	}

	public record MarkerDiag(
			String id,
			String texture,
			String position,
			double distance,
			int hp,
			int maxHp,
			String status,
			boolean renderedFlag,
			boolean renderedText,
			boolean renderedHp) {
		String format() {
			return String.format(
					Locale.ROOT,
					"%s tex=%s pos=%s dist=%.1f hp=%d/%d status=%s flag=%s text=%s hpBar=%s",
					id,
					texture,
					position,
					distance,
					hp,
					maxHp,
					status,
					renderedFlag ? "yes" : "no",
					renderedText ? "yes" : "no",
					renderedHp ? "yes" : "no");
		}
	}
}
