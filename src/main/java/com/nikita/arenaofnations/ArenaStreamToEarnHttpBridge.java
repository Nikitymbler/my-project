package com.nikita.arenaofnations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Local loopback HTTP bridge for StreamToEarn.
 * HTTP thread only calls {@link ArenaStreamToEarnCommands#acceptChatPayload}/{@link ArenaStreamToEarnCommands#acceptGiftPayload}.
 * HTTP settings are read at Minecraft server start; changing them requires a server restart.
 */
public final class ArenaStreamToEarnHttpBridge {
	private static final String SAFE_BIND_HOST = "127.0.0.1";
	private static final String TOKEN_HEADER = "X-Arena-Token";
	private static final String SEPARATOR = "|||";
	private static final int MAX_BODY_BYTES = 1000;
	private static final int MAX_COMPAT_TOKEN_BYTES = 256;
	private static final int MAX_COMPAT_PAYLOAD_BYTES = 1000;
	/** Max full body for body-auth endpoints (token + separator + payload). */
	private static final int MAX_COMPAT_FULL_BODY_BYTES = 1260;
	private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);

	private static final Object LOCK = new Object();
	private static HttpServer httpServer;
	private static ExecutorService executor;
	private static volatile boolean running;
	private static volatile String runningBindAddress = SAFE_BIND_HOST;
	private static volatile int runningPort = 8765;

	private ArenaStreamToEarnHttpBridge() {
	}

	public static void register() {
		if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> startHttpServer());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopHttpServer());
	}

	public static boolean isRunning() {
		return running;
	}

	public static int getConfiguredPort() {
		return ArenaConfig.get().getS2eHttpPort();
	}

	public static String getBindAddress() {
		return runningBindAddress;
	}

	public static int getRunningPort() {
		return runningPort;
	}

	public static String getOverlayUrl() {
		return "http://" + runningBindAddress + ":" + runningPort + "/overlay";
	}

	public static String getTikTokOverlayUrl() {
		return "http://" + runningBindAddress + ":" + runningPort + "/overlay/tiktok";
	}

	public static boolean isTokenConfigured() {
		return ArenaConfig.get().isS2eHttpTokenConfigured();
	}

	public static void restartServer() {
		stopHttpServer();
		startHttpServer();
	}

	private static void startHttpServer() {
		synchronized (LOCK) {
			if (running) {
				return;
			}

			ArenaConfig config = ArenaConfig.get();
			boolean overlayEnabled = config.isOverlayEnabled();
			boolean s2eEnabled = config.isS2eHttpEnabled();
			if (!overlayEnabled && !s2eEnabled) {
				ArenaOfNations.LOGGER.info("Local HTTP bridge disabled (overlay_enabled=false and s2e_http_enabled=false).");
				return;
			}
			if (s2eEnabled && !config.isS2eHttpTokenConfigured()) {
				ArenaOfNations.LOGGER.warn(
						"StreamToEarn HTTP bridge enabled but s2e_http_token is empty; S2E endpoints disabled.");
				s2eEnabled = false;
			}
			if (!overlayEnabled && !s2eEnabled) {
				return;
			}

			String bindHost = resolveSafeBindHost(config.getOverlayBindAddress());
			int port = overlayEnabled ? config.getOverlayPort() : config.getS2eHttpPort();
			try {
				InetSocketAddress address = new InetSocketAddress(bindHost, port);
				HttpServer server = HttpServer.create(address, 0);
				server.createContext("/arena/health", ArenaStreamToEarnHttpBridge::handleHealth);
				if (s2eEnabled) {
					server.createContext("/arena/chat", exchange -> handlePayload(exchange, true));
					server.createContext("/arena/gift", exchange -> handlePayload(exchange, false));
					server.createContext("/arena/streamtoearn/chat", exchange -> handleStreamToEarnBodyAuth(exchange, true));
					server.createContext("/arena/streamtoearn/gift", exchange -> handleStreamToEarnBodyAuth(exchange, false));
				}
				if (overlayEnabled) {
					server.createContext("/api/arena/state", ArenaStreamToEarnHttpBridge::handleOverlayState);
					server.createContext("/overlay", ArenaStreamToEarnHttpBridge::handleOverlayIndex);
					server.createContext("/overlay/", ArenaStreamToEarnHttpBridge::handleOverlayAsset);
				}

				ExecutorService httpExecutor = Executors.newCachedThreadPool(daemonFactory());
				server.setExecutor(httpExecutor);
				server.start();

				httpServer = server;
				executor = httpExecutor;
				running = true;
				runningBindAddress = bindHost;
				runningPort = port;

				ArenaOfNations.LOGGER.info(
						"Local HTTP bridge started on http://{}:{} (overlay={}, s2e={})",
						bindHost,
						port,
						overlayEnabled,
						s2eEnabled);
			} catch (Exception e) {
				running = false;
				httpServer = null;
				shutdownExecutorQuietly();
				ArenaOfNations.LOGGER.error(
						"Failed to start StreamToEarn HTTP bridge on {}:{} — mod continues without HTTP bridge",
						bindHost,
						port,
						e);
			}
		}
	}

	private static void stopHttpServer() {
		synchronized (LOCK) {
			if (httpServer != null) {
				try {
					httpServer.stop(0);
				} catch (Exception e) {
					ArenaOfNations.LOGGER.warn("Error while stopping StreamToEarn HTTP bridge", e);
				}
				httpServer = null;
			}
			shutdownExecutorQuietly();
			if (running) {
				running = false;
				ArenaOfNations.LOGGER.info("StreamToEarn HTTP bridge stopped.");
			} else {
				running = false;
			}
		}
	}

	private static void shutdownExecutorQuietly() {
		ExecutorService current = executor;
		executor = null;
		if (current != null) {
			current.shutdownNow();
		}
	}

	private static ThreadFactory daemonFactory() {
		return runnable -> {
			Thread thread = new Thread(runnable, "arena-s2e-http");
			thread.setDaemon(true);
			return thread;
		};
	}

	private static void handleHealth(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
				return;
			}
			sendJson(exchange, 200, "{\"ok\":true,\"service\":\"arena-of-nations-s2e\"}", null);
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("StreamToEarn HTTP /arena/health failed", e);
			sendJson(exchange, 500, "{\"accepted\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	private static void handlePayload(HttpExchange exchange, boolean chat) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if (!"POST".equalsIgnoreCase(method)) {
				sendJson(exchange, 405, "{\"accepted\":false,\"reason\":\"method_not_allowed\"}", "POST");
				return;
			}

			if (!isAuthorized(exchange)) {
				sendJson(exchange, 401, "{\"accepted\":false,\"reason\":\"unauthorized\"}", null);
				return;
			}

			String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
			if (contentLengthHeader != null) {
				try {
					long contentLength = Long.parseLong(contentLengthHeader.trim());
					if (contentLength > MAX_BODY_BYTES) {
						drainAndClose(exchange.getRequestBody());
						sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
						return;
					}
				} catch (NumberFormatException ignored) {
					// Fall through to bounded body read.
				}
			}

			byte[] bodyBytes = readBodyLimited(exchange.getRequestBody(), MAX_BODY_BYTES);
			if (bodyBytes == null) {
				sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
				return;
			}

			String payload = new String(bodyBytes, StandardCharsets.UTF_8);
			ArenaStreamToEarnCommands.AcceptResult result = chat
					? ArenaStreamToEarnCommands.acceptChatPayload(payload)
					: ArenaStreamToEarnCommands.acceptGiftPayload(payload);

			if (result.accepted()) {
				sendJson(exchange, 202, jsonAccepted(true, result.reason()), null);
			} else {
				sendJson(exchange, 400, jsonAccepted(false, result.reason()), null);
			}
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("StreamToEarn HTTP payload endpoint failed", e);
			sendJson(exchange, 500, "{\"accepted\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	/**
	 * StreamToEarn-compatible body-auth endpoints (no custom headers in S2E UI).
	 * Accepts plain-text {@code <token>|||<payload>} or JSON object with token/viewerId/... fields.
	 */
	private static void handleStreamToEarnBodyAuth(HttpExchange exchange, boolean chat) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"accepted\":false,\"reason\":\"method_not_allowed\"}", "POST");
				return;
			}

			String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
			if (contentLengthHeader != null) {
				try {
					long contentLength = Long.parseLong(contentLengthHeader.trim());
					if (contentLength > MAX_COMPAT_FULL_BODY_BYTES) {
						drainAndClose(exchange.getRequestBody());
						sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
						return;
					}
				} catch (NumberFormatException ignored) {
					// Fall through to bounded body read.
				}
			}

			byte[] bodyBytes = readBodyLimited(exchange.getRequestBody(), MAX_COMPAT_FULL_BODY_BYTES);
			if (bodyBytes == null) {
				sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
				return;
			}

			String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
			int firstNonWs = indexOfFirstNonWhitespace(bodyText);
			if (firstNonWs >= 0 && bodyText.charAt(firstNonWs) == '{') {
				handleJsonBodyAuth(exchange, chat, bodyText);
			} else {
				handlePlainTextBodyAuth(exchange, chat, bodyBytes);
			}
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("StreamToEarn HTTP body-auth endpoint failed", e);
			sendJson(exchange, 500, "{\"accepted\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	private static void handlePlainTextBodyAuth(HttpExchange exchange, boolean chat, byte[] bodyBytes)
			throws IOException {
		int sepIndex = indexOfSeparator(bodyBytes);
		if (sepIndex < 0) {
			sendJson(exchange, 400, jsonAccepted(false, "missing_separator"), null);
			return;
		}

		int tokenLen = sepIndex;
		int payloadOffset = sepIndex + SEPARATOR.length();
		int payloadLen = bodyBytes.length - payloadOffset;

		if (tokenLen > MAX_COMPAT_TOKEN_BYTES || payloadLen > MAX_COMPAT_PAYLOAD_BYTES) {
			sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
			return;
		}

		byte[] tokenBytes = new byte[tokenLen];
		System.arraycopy(bodyBytes, 0, tokenBytes, 0, tokenLen);
		byte[] payloadBytes = new byte[payloadLen];
		if (payloadLen > 0) {
			System.arraycopy(bodyBytes, payloadOffset, payloadBytes, 0, payloadLen);
		}

		if (tokenLen == 0 || !tokenMatches(tokenBytes)) {
			sendJson(exchange, 401, "{\"accepted\":false,\"reason\":\"unauthorized\"}", null);
			return;
		}

		String payload = new String(payloadBytes, StandardCharsets.UTF_8);
		respondAcceptResult(exchange, chat, payload);
	}

	private static void handleJsonBodyAuth(HttpExchange exchange, boolean chat, String bodyText) throws IOException {
		JsonObject obj;
		try {
			JsonElement root = JsonParser.parseString(bodyText);
			if (!root.isJsonObject()) {
				sendJson(exchange, 400, jsonAccepted(false, "malformed_json"), null);
				return;
			}
			obj = root.getAsJsonObject();
		} catch (JsonParseException e) {
			sendJson(exchange, 400, jsonAccepted(false, "malformed_json"), null);
			return;
		}

		String token = readJsonStringField(obj, "token", true);
		if (token == null) {
			sendJson(exchange, 400, jsonAccepted(false, "missing_field"), null);
			return;
		}
		byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
		if (tokenBytes.length > MAX_COMPAT_TOKEN_BYTES) {
			sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
			return;
		}
		if (token.isEmpty() || !tokenMatches(tokenBytes)) {
			sendJson(exchange, 401, "{\"accepted\":false,\"reason\":\"unauthorized\"}", null);
			return;
		}

		String viewerId = readJsonStringField(obj, "viewerId", true);
		if (viewerId == null || viewerId.isEmpty()) {
			sendJson(exchange, 400, jsonAccepted(false, "missing_field"), null);
			return;
		}

		String payload;
		if (chat) {
			String message = readJsonStringField(obj, "message", true);
			if (message == null) {
				sendJson(exchange, 400, jsonAccepted(false, "missing_field"), null);
				return;
			}
			payload = viewerId + SEPARATOR + message;
		} else {
			Integer coins = readJsonCoinsField(obj);
			if (coins == null) {
				sendJson(exchange, 400, jsonAccepted(false, "missing_field"), null);
				return;
			}
			String eventId = readJsonStringField(obj, "eventId", false);
			if (eventId != null && !eventId.isEmpty()) {
				payload = viewerId + SEPARATOR + coins + SEPARATOR + eventId;
			} else {
				payload = viewerId + SEPARATOR + coins;
			}
		}

		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
		if (payloadBytes.length > MAX_COMPAT_PAYLOAD_BYTES) {
			sendJson(exchange, 413, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
			return;
		}

		respondAcceptResult(exchange, chat, payload);
	}

	/**
	 * @param required if true, missing/null/wrong-type returns {@code null}; if false, missing returns {@code null},
	 *                 wrong-type returns {@code null}, present string returns value
	 */
	private static String readJsonStringField(JsonObject obj, String field, boolean required) {
		if (!obj.has(field) || obj.get(field).isJsonNull()) {
			return null;
		}
		JsonElement element = obj.get(field);
		if (!element.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (!primitive.isString()) {
			return null;
		}
		return primitive.getAsString();
	}

	/**
	 * @return parsed coins, or {@code null} if missing/invalid
	 */
	private static Integer readJsonCoinsField(JsonObject obj) {
		if (!obj.has("coins") || obj.get("coins").isJsonNull()) {
			return null;
		}
		JsonElement element = obj.get("coins");
		if (!element.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		String raw;
		if (primitive.isNumber() || primitive.isString()) {
			raw = primitive.getAsString().trim();
		} else {
			return null;
		}
		if (raw.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void respondAcceptResult(HttpExchange exchange, boolean chat, String payload) throws IOException {
		ArenaStreamToEarnCommands.AcceptResult result = chat
				? ArenaStreamToEarnCommands.acceptChatPayload(payload)
				: ArenaStreamToEarnCommands.acceptGiftPayload(payload);

		if (result.accepted()) {
			sendJson(exchange, 202, jsonAccepted(true, result.reason()), null);
		} else {
			sendJson(exchange, 400, jsonAccepted(false, result.reason()), null);
		}
	}

	private static int indexOfFirstNonWhitespace(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int indexOfSeparator(byte[] body) {
		byte[] sep = SEPARATOR.getBytes(StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i <= body.length - sep.length; i++) {
			for (int j = 0; j < sep.length; j++) {
				if (body[i + j] != sep[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static boolean isAuthorized(HttpExchange exchange) {
		String provided = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
		if (provided == null) {
			return false;
		}
		return tokenMatches(provided.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean tokenMatches(byte[] providedBytes) {
		String expected = ArenaConfig.get().getS2eHttpToken();
		if (expected == null || expected.isEmpty()) {
			return false;
		}
		byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expectedBytes, providedBytes);
	}

	private static String resolveSafeBindHost(String configured) {
		String value = configured == null ? "" : configured.trim();
		if (!SAFE_BIND_HOST.equals(value)) {
			ArenaOfNations.LOGGER.warn("Unsafe overlay bind address '{}', forcing {}", value, SAFE_BIND_HOST);
			return SAFE_BIND_HOST;
		}
		return value;
	}

	private static void handleOverlayState(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
				return;
			}
			ArenaOverlayStateService.get().markAndGetRequestCount();
			byte[] bytes = ArenaOverlayStateService.get().snapshotJson().getBytes(StandardCharsets.UTF_8);
			Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", "application/json; charset=utf-8");
			headers.set("Cache-Control", "no-store");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("Overlay state endpoint failed", e);
			sendJson(exchange, 500, "{\"ok\":false,\"reason\":\"internal_error\"}", null);
		}
	}

	private static void handleOverlayIndex(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
			return;
		}
		writeResource(exchange, "assets/arena_of_nations/overlay/index.html", "text/html; charset=utf-8");
	}

	private static void handleOverlayAsset(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendJson(exchange, 405, "{\"ok\":false,\"reason\":\"method_not_allowed\"}", "GET");
			return;
		}
		String path = exchange.getRequestURI().getPath();
		String suffix = path.substring("/overlay/".length());
		if (suffix.isBlank() || suffix.contains("..")) {
			writeResource(exchange, "assets/arena_of_nations/overlay/index.html", "text/html; charset=utf-8");
			return;
		}
		// Directory routes: /overlay/tiktok and /overlay/tiktok/ → tiktok/index.html
		if ("tiktok".equals(suffix) || "tiktok/".equals(suffix)) {
			writeResource(exchange, "assets/arena_of_nations/overlay/tiktok/index.html", "text/html; charset=utf-8");
			return;
		}
		String resourcePath = "assets/arena_of_nations/overlay/" + suffix;
		if (suffix.endsWith("/")) {
			resourcePath = resourcePath + "index.html";
		}
		String mime = guessMime(resourcePath);
		writeResource(exchange, resourcePath, mime);
	}

	private static void writeResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
		try (InputStream in = ArenaStreamToEarnHttpBridge.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (in == null) {
				sendJson(exchange, 404, "{\"ok\":false,\"reason\":\"not_found\"}", null);
				return;
			}
			byte[] bytes = in.readAllBytes();
			Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", contentType);
			headers.set("Cache-Control", "no-store");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	private static String guessMime(String path) {
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
		if (lower.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		return "application/octet-stream";
	}

	/**
	 * @return body bytes, or {@code null} if body exceeds {@code maxBytes}
	 */
	private static byte[] readBodyLimited(InputStream input, int maxBytes) throws IOException {
		try (InputStream in = input) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 256));
			byte[] chunk = new byte[256];
			int total = 0;
			int read;
			while ((read = in.read(chunk)) != -1) {
				total += read;
				if (total > maxBytes) {
					// Drain remainder so the connection can close cleanly.
					while (in.read(chunk) != -1) {
						// discard
					}
					return null;
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
	}

	private static void drainAndClose(InputStream input) {
		if (input == null) {
			return;
		}
		try (InputStream in = input) {
			byte[] chunk = new byte[256];
			while (in.read(chunk) != -1) {
				// discard
			}
		} catch (IOException ignored) {
			// ignore
		}
	}

	private static String jsonAccepted(boolean accepted, String reason) {
		String safeReason = reason == null || reason.isBlank()
				? (accepted ? "queued" : "rejected")
				: reason;
		return "{\"accepted\":" + accepted + ",\"reason\":\"" + escapeJson(safeReason) + "\"}";
	}

	private static String escapeJson(String value) {
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

	private static void sendJson(HttpExchange exchange, int status, String json, String allowMethod)
			throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "application/json; charset=utf-8");
		headers.set("Cache-Control", "no-store");
		if (allowMethod != null) {
			headers.set("Allow", allowMethod);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
