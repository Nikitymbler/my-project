package com.nikita.arenaofnations.client;

import java.util.List;
import java.util.Locale;

import com.nikita.arenaofnations.ArenaBaseMarkerLayout;

/**
 * Client toggle and diagnostics for world-space base markers / country labels.
 */
public final class ArenaBaseMarkerSettings {
	public static final double MAX_RENDER_DISTANCE = 120.0D;
	public static final double FADE_START_DISTANCE = 100.0D;

	private static boolean enabled = true;
	private static int lastActiveMarkers;
	private static int lastRenderedMarkers;
	private static List<MarkerDiag> lastDiags = List.of();

	private static int labelsExpected;
	private static int labelsRenderAttempted;
	private static int labelsActuallyDrawn;
	private static List<String> lastRenderedCountryLabels = List.of();
	private static String lastLabelWorldPositionRU = "-";
	private static String lastLabelDistanceRU = "-";
	private static String lastLabelRenderError = "-";

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

	public static void recordLabelRenderStats(
			int expected,
			int attempted,
			int drawn,
			List<String> labels,
			String ruWorldPos,
			String ruDistance,
			String error) {
		labelsExpected = Math.max(0, expected);
		labelsRenderAttempted = Math.max(0, attempted);
		labelsActuallyDrawn = Math.max(0, drawn);
		lastRenderedCountryLabels = labels == null ? List.of() : List.copyOf(labels);
		lastLabelWorldPositionRU = ruWorldPos == null || ruWorldPos.isBlank() ? "-" : ruWorldPos;
		lastLabelDistanceRU = ruDistance == null || ruDistance.isBlank() ? "-" : ruDistance;
		lastLabelRenderError = error == null || error.isBlank() ? "-" : error;
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
				.append("baseCountryLabelsExpected=").append(labelsExpected).append('\n')
				.append("baseCountryLabelsRenderAttempted=").append(labelsRenderAttempted).append('\n')
				.append("baseCountryLabelsActuallyDrawn=").append(labelsActuallyDrawn).append('\n')
				.append("lastRenderedCountryLabels=")
				.append(lastRenderedCountryLabels.isEmpty() ? "-" : String.join(",", lastRenderedCountryLabels))
				.append('\n')
				.append("lastLabelWorldPositionRU=").append(lastLabelWorldPositionRU).append('\n')
				.append("lastLabelDistanceRU=").append(lastLabelDistanceRU).append('\n')
				.append("lastLabelRenderError=").append(lastLabelRenderError).append('\n')
				.append("nameDisplayMode=SEE_THROUGH\n")
				.append("namePackedLight=FULL_BRIGHT\n")
				.append("nameWorldGap=").append(ArenaBaseMarkerLayout.NAME_WORLD_GAP).append('\n')
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
			boolean renderedHp,
			String hideReason) {
		String format() {
			return String.format(
					Locale.ROOT,
					"%s tex=%s pos=%s dist=%.1f hp=%d/%d status=%s flag=%s text=%s hpBar=%s hide=%s",
					id,
					texture,
					position,
					distance,
					hp,
					maxHp,
					status,
					renderedFlag ? "yes" : "no",
					renderedText ? "yes" : "no",
					renderedHp ? "yes" : "no",
					hideReason == null ? "none" : hideReason);
		}
	}
}
