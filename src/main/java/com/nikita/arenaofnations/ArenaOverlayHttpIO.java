package com.nikita.arenaofnations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/**
 * Shared overlay HTTP handlers used by the public overlay-only server (and local diagnostics).
 * Does not register StreamToEarn gift/chat routes.
 */
public final class ArenaOverlayHttpIO {
	private static final AtomicLong LAST_STATE_REQUEST_GAME_TIME = new AtomicLong(-1L);
	private static final AtomicReference<String> LAST_ERROR = new AtomicReference<>("");

	private ArenaOverlayHttpIO() {
	}

	public static long lastStateRequestGameTime() {
		return LAST_STATE_REQUEST_GAME_TIME.get();
	}

	public static String lastError() {
		String value = LAST_ERROR.get();
		return value == null ? "" : value;
	}

	public static void clearLastError() {
		LAST_ERROR.set("");
	}

	public static void handleHealth(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
				return;
			}
			boolean overlayReady = ArenaOverlayHttpServer.isRunning();
			String snap = ArenaOverlayStateService.get().snapshotJson();
			boolean snapshotAvailable = snap != null && !snap.isBlank() && snap.contains("\"phase\"");
			String version = ArenaOfNations.MOD_ID + "/1.0.0";
			String json = "{\"ok\":true,\"service\":\"arena-overlay\",\"serverRunning\":"
					+ overlayReady
					+ ",\"overlayReady\":"
					+ overlayReady
					+ ",\"snapshotAvailable\":"
					+ snapshotAvailable
					+ ",\"version\":\""
					+ escapeJson(version)
					+ "\"}";
			sendJson(exchange, 200, json, null);
		} catch (Exception e) {
			LAST_ERROR.set("health:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay HTTP /arena/health failed", e);
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	public static void handleOverlayState(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
				return;
			}
			LAST_STATE_REQUEST_GAME_TIME.set(System.currentTimeMillis());
			ArenaOverlayStateService.get().markAndGetRequestCount();
			String snap = ArenaOverlayStateService.get().snapshotJson();
			if (snap == null || snap.isBlank()) {
				snap = "{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[]}";
			}
			byte[] bytes = snap.getBytes(StandardCharsets.UTF_8);
			Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", "application/json; charset=utf-8");
			headers.set("Cache-Control", "no-store");
			headers.set("X-Content-Type-Options", "nosniff");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			clearLastError();
		} catch (Exception e) {
			LAST_ERROR.set("state:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay state endpoint failed", e);
			// Never leave the browser without a valid empty payload.
			sendJson(exchange, 200, "{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[]}", null);
		}
	}

	public static void handleOverlayRoot(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
			return;
		}
		writeResource(exchange, "assets/arena_of_nations/overlay/index.html", "text/html; charset=utf-8", false);
	}

	public static void handleOverlayAsset(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
			return;
		}
		String path = exchange.getRequestURI().getPath();
		if (!path.startsWith("/overlay/")) {
			sendJson(exchange, 404, "{\"ok\":false,\"reason\":\"not_found\"}", null);
			return;
		}
		String suffix = path.substring("/overlay/".length());
		if (suffix.isBlank() || suffix.contains("..")) {
			writeResource(exchange, "assets/arena_of_nations/overlay/index.html", "text/html; charset=utf-8", false);
			return;
		}
		if (suffix.startsWith("api/")) {
			sendJson(exchange, 404, "{\"ok\":false,\"reason\":\"not_found\"}", null);
			return;
		}
		if ("tiktok".equals(suffix) || "tiktok/".equals(suffix)) {
			writeResource(exchange, "assets/arena_of_nations/overlay/tiktok/index.html", "text/html; charset=utf-8", false);
			return;
		}
		String resourcePath = "assets/arena_of_nations/overlay/" + suffix;
		if (suffix.endsWith("/")) {
			resourcePath = resourcePath + "index.html";
		}
		writeResource(exchange, resourcePath, guessMime(resourcePath), isCacheableAsset(resourcePath));
	}

	public static void handleLayoutGet(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
				return;
			}
			ArenaOverlayLayoutConfig.ensureLoaded();
			sendJson(exchange, 200, ArenaOverlayLayoutConfig.currentJson(), null);
		} catch (Exception e) {
			LAST_ERROR.set("layout_get:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay layout GET failed", e);
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	public static void handleLayoutPost(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "POST");
				return;
			}
			String body = readBody(exchange);
			ArenaOverlayLayoutConfig.LayoutState parsed;
			try {
				parsed = ArenaOverlayLayoutConfig.parse(body);
			} catch (IllegalArgumentException bad) {
				sendJson(exchange, 400, "{\"ok\":false,\"reason\":\"invalid_json\"}", null);
				return;
			}
			try {
				ArenaOverlayLayoutConfig.LayoutState saved = ArenaOverlayLayoutConfig.save(parsed);
				sendJson(exchange, 200, ArenaOverlayLayoutConfig.toJson(saved), null);
			} catch (IOException io) {
				ArenaOverlayLayoutConfig.markSaveFailed(io.getClass().getSimpleName());
				LAST_ERROR.set("layout_save:" + io.getClass().getSimpleName());
				ArenaOfNations.LOGGER.error("Overlay layout POST save failed", io);
				sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"save_failed\"}", null);
			}
		} catch (Exception e) {
			LAST_ERROR.set("layout_post:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay layout POST failed", e);
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	public static void handleLayoutReset(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "POST");
				return;
			}
			try {
				ArenaOverlayLayoutConfig.LayoutState saved = ArenaOverlayLayoutConfig.resetToDefaults();
				sendJson(exchange, 200, ArenaOverlayLayoutConfig.toJson(saved), null);
			} catch (IOException io) {
				ArenaOverlayLayoutConfig.markSaveFailed(io.getClass().getSimpleName());
				LAST_ERROR.set("layout_reset:" + io.getClass().getSimpleName());
				ArenaOfNations.LOGGER.error("Overlay layout reset failed", io);
				sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"save_failed\"}", null);
			}
		} catch (Exception e) {
			LAST_ERROR.set("layout_reset:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay layout reset failed", e);
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	public static void handleStatsResetRoundWins(HttpExchange exchange) throws IOException {
		handleStatsReset(exchange, ArenaStatsResetService.ResetType.ROUND_WINS);
	}

	public static void handleStatsResetScorePoints(HttpExchange exchange) throws IOException {
		handleStatsReset(exchange, ArenaStatsResetService.ResetType.SCORE_POINTS);
	}

	public static void handleStatsResetFighterRecord(HttpExchange exchange) throws IOException {
		handleStatsReset(exchange, ArenaStatsResetService.ResetType.FIGHTER_RECORD);
	}

	public static void handleStatsResetAll(HttpExchange exchange) throws IOException {
		handleStatsReset(exchange, ArenaStatsResetService.ResetType.ALL);
	}

	public static void handleReserveSettingsGet(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"success\":false,\"message\":\"method_not_allowed\"}", "GET");
				return;
			}
			ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
			String json = "{\"success\":true"
					+ ",\"reserveReleaseBatch\":" + settings.getReserveReleaseBatch()
					+ ",\"minimum\":" + ArenaReserveRuntimeSettings.MIN_BATCH
					+ ",\"maximum\":" + ArenaReserveRuntimeSettings.MAX_BATCH
					+ ",\"reserveReleaseIntervalTicks\":" + settings.getReserveReleaseIntervalTicks()
					+ ",\"activeFightersLimit\":" + settings.getActiveFightersLimit()
					+ ",\"liveEditable\":true}";
			sendJson(exchange, 200, json, null);
		} catch (Exception e) {
			LAST_ERROR.set("reserve_settings_get:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay reserve-settings GET failed", e);
			sendJson(exchange, 500, "{\"success\":false,\"message\":\"internal_error\"}", null);
		}
	}

	public static void handleReserveSettingsPost(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"success\":false,\"message\":\"method_not_allowed\"}", "POST");
				return;
			}
			String body = readBody(exchange);
			Integer batch;
			try {
				batch = parseReserveReleaseBatch(body);
			} catch (IllegalArgumentException bad) {
				sendJson(
						exchange,
						400,
						"{\"success\":false,\"message\":\"" + escapeJson(bad.getMessage()) + "\"}",
						null);
				return;
			}
			ArenaReserveRuntimeSettings.ApplyResult result = ArenaReserveRuntimeSettings.get()
					.applyOnServerThread(batch, ArenaReserveRuntimeSettings.ChangedBy.BROWSER);
			String json = "{\"success\":" + result.success()
					+ ",\"reserveReleaseBatch\":" + result.reserveReleaseBatch()
					+ ",\"message\":\"" + escapeJson(result.message()) + "\"}";
			sendJson(exchange, result.httpStatus(), json, null);
		} catch (Exception e) {
			LAST_ERROR.set("reserve_settings_post:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay reserve-settings POST failed", e);
			sendJson(exchange, 500, "{\"success\":false,\"message\":\"internal_error\"}", null);
		}
	}

	/**
	 * Parses {@code reserveReleaseBatch} strictly: must be a JSON number integer in range.
	 * Rejects missing, null, string, fractional, and out-of-range values (no silent clamp).
	 */
	static Integer parseReserveReleaseBatch(String body) {
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("Некорректный JSON");
		}
		com.google.gson.JsonObject root;
		try {
			com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(body);
			if (parsed == null || !parsed.isJsonObject()) {
				throw new IllegalArgumentException("Некорректный JSON");
			}
			root = parsed.getAsJsonObject();
		} catch (com.google.gson.JsonSyntaxException e) {
			throw new IllegalArgumentException("Некорректный JSON");
		}
		if (!root.has("reserveReleaseBatch")) {
			throw new IllegalArgumentException("Отсутствует reserveReleaseBatch");
		}
		com.google.gson.JsonElement el = root.get("reserveReleaseBatch");
		if (el == null || el.isJsonNull()) {
			throw new IllegalArgumentException("Отсутствует reserveReleaseBatch");
		}
		if (!el.isJsonPrimitive()) {
			throw new IllegalArgumentException("reserveReleaseBatch должен быть целым числом");
		}
		com.google.gson.JsonPrimitive primitive = el.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			throw new IllegalArgumentException("reserveReleaseBatch должен быть целым числом");
		}
		double asDouble = primitive.getAsDouble();
		if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.rint(asDouble)) {
			throw new IllegalArgumentException("reserveReleaseBatch должен быть целым числом");
		}
		long asLong = primitive.getAsLong();
		if (asLong < ArenaReserveRuntimeSettings.MIN_BATCH || asLong > ArenaReserveRuntimeSettings.MAX_BATCH) {
			throw new IllegalArgumentException(
					"Допустимое значение: от "
							+ ArenaReserveRuntimeSettings.MIN_BATCH
							+ " до "
							+ ArenaReserveRuntimeSettings.MAX_BATCH
							+ ".");
		}
		return (int) asLong;
	}

	private static void handleStatsReset(HttpExchange exchange, ArenaStatsResetService.ResetType type)
			throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"success\":false,\"message\":\"method_not_allowed\"}", "POST");
				return;
			}
			String body = readBody(exchange);
			Boolean confirmed;
			try {
				confirmed = parseConfirm(body);
			} catch (IllegalArgumentException bad) {
				sendJson(exchange, 400, "{\"success\":false,\"message\":\"invalid_json\"}", null);
				return;
			}
			if (!Boolean.TRUE.equals(confirmed)) {
				sendJson(exchange, 400, "{\"success\":false,\"message\":\"confirm_required\"}", null);
				return;
			}
			net.minecraft.server.MinecraftServer server = ArenaOverlayHttpServer.getActiveServer();
			if (server == null) {
				sendJson(exchange, 500, "{\"success\":false,\"message\":\"no_server\"}", null);
				return;
			}
			ArenaStatsResetService.Result result = ArenaStatsResetService.reset(server, type);
			String json = "{\"success\":" + result.success()
					+ ",\"message\":\"" + escapeJson(result.message()) + "\"}";
			sendJson(exchange, result.httpStatus(), json, null);
		} catch (Exception e) {
			LAST_ERROR.set("stats_reset:" + e.getClass().getSimpleName());
			ArenaOfNations.LOGGER.error("Overlay stats reset failed", e);
			sendJson(exchange, 500, "{\"success\":false,\"message\":\"internal_error\"}", null);
		}
	}

	/** @return true/false for confirm; throws IllegalArgumentException on invalid JSON */
	private static Boolean parseConfirm(String body) {
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("empty_json");
		}
		try {
			com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
			if (!root.has("confirm") || !root.get("confirm").isJsonPrimitive()) {
				return false;
			}
			return root.get("confirm").getAsBoolean();
		} catch (com.google.gson.JsonSyntaxException | IllegalStateException e) {
			throw new IllegalArgumentException("invalid_json");
		}
	}

	private static String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] bytes = in.readAllBytes();
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	public static void writeResource(
			HttpExchange exchange,
			String resourcePath,
			String contentType,
			boolean cacheable) throws IOException {
		try (InputStream in = ArenaOverlayHttpIO.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (in == null) {
				sendJson(exchange, 404, "{\"ok\":false,\"reason\":\"not_found\"}", null);
				return;
			}
			byte[] bytes = in.readAllBytes();
			Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", contentType);
			headers.set("X-Content-Type-Options", "nosniff");
			if (cacheable) {
				headers.set("Cache-Control", "public, max-age=86400");
			} else if (contentType != null && contentType.startsWith("text/html")) {
				headers.set("Cache-Control", "no-cache");
			} else {
				// CSS/JS: no-store so OBS/TikTok CEF picks up redesigns.
				headers.set("Cache-Control", "no-store");
			}
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	public static void sendJson(HttpExchange exchange, int status, String json, String allowMethod)
			throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "application/json; charset=utf-8");
		headers.set("Cache-Control", "no-store");
		headers.set("X-Content-Type-Options", "nosniff");
		if (allowMethod != null) {
			headers.set("Allow", allowMethod);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	public static String guessMime(String path) {
		String lower = path.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".css")) {
			return "text/css; charset=utf-8";
		}
		if (lower.endsWith(".js")) {
			return "application/javascript; charset=utf-8";
		}
		if (lower.endsWith(".svg")) {
			return "image/svg+xml";
		}
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lower.endsWith(".webp")) {
			return "image/webp";
		}
		if (lower.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		return "application/octet-stream";
	}

	private static boolean isCacheableAsset(String resourcePath) {
		String lower = resourcePath.toLowerCase(Locale.ROOT);
		// Cache only static images (flags). Overlay HTML/CSS/JS are no-store for OBS/TikTok CEF.
		return lower.endsWith(".png")
				|| lower.endsWith(".svg")
				|| lower.endsWith(".jpg")
				|| lower.endsWith(".jpeg")
				|| lower.endsWith(".webp");
	}

	public static String escapeJson(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			switch (ch) {
				case '\\' -> builder.append("\\\\");
				case '"' -> builder.append("\\\"");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (ch < 0x20) {
						builder.append(String.format("\\u%04x", (int) ch));
					} else {
						builder.append(ch);
					}
				}
			}
		}
		return builder.toString();
	}
}
