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
			String version = ArenaOfNations.MOD_ID + "/1.0.0";
			String json = "{\"ok\":true,\"service\":\"arena-overlay\",\"version\":\""
					+ escapeJson(version)
					+ "\",\"serverRunning\":"
					+ overlayReady
					+ ",\"overlayReady\":"
					+ overlayReady
					+ "}";
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
			byte[] bytes = ArenaOverlayStateService.get().snapshotJson().getBytes(StandardCharsets.UTF_8);
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
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
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
			} else {
				// HTML/CSS/JS must not stick in OBS CEF for a day — stale JS breaks after overlay redesigns.
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
