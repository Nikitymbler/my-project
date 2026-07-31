package com.nikita.arenaofnations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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
	private static volatile boolean s2eEndpointsActive;
	private static volatile String runningBindAddress = SAFE_BIND_HOST;
	private static volatile int runningPort = 8765;

	private ArenaStreamToEarnHttpBridge() {
	}

	public static void register() {
		if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> startHttpServer());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// Stop accepting HTTP first, then clear the queue (avoids enqueue-after-clear race).
			stopHttpServer();
			ArenaViewerEventManager.get().clearTransientState();
		});
	}

	public static boolean isRunning() {
		return running;
	}

	/** True when gift/chat HTTP endpoints are registered (requires s2e_http_enabled + token). */
	public static boolean areS2eEndpointsActive() {
		return s2eEndpointsActive;
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
		return "http://" + ArenaOverlayHttpServer.getBindAddress() + ":"
				+ ArenaOverlayHttpServer.getRunningPort() + "/overlay";
	}

	public static String getTikTokOverlayUrl() {
		return ArenaOverlayHttpServer.getLocalTikTokUrl();
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
			boolean s2eEnabled = config.isS2eHttpEnabled();
			if (!s2eEnabled) {
				ArenaOfNations.LOGGER.info("StreamToEarn HTTP bridge disabled (s2e_http_enabled=false).");
				return;
			}
			if (!config.isS2eHttpTokenConfigured()) {
				ArenaOfNations.LOGGER.warn(
						"StreamToEarn HTTP bridge enabled but s2e_http_token is empty; bridge not started.");
				return;
			}

			String bindHost = resolveSafeBindHost(config.getOverlayBindAddress());
			int port = config.getS2eHttpPort();
			HttpServer server = null;
			try {
				InetSocketAddress address = new InetSocketAddress(bindHost, port);
				server = HttpServer.create(address, 0);
				server.createContext("/arena/health", ArenaStreamToEarnHttpBridge::handleHealth);
				server.createContext("/arena/chat", exchange -> handlePayload(exchange, true));
				server.createContext("/arena/gift", exchange -> handlePayload(exchange, false));
				server.createContext("/arena/streamtoearn/chat", exchange -> handleStreamToEarnBodyAuth(exchange, true));
				server.createContext("/arena/streamtoearn/gift", exchange -> handleStreamToEarnBodyAuth(exchange, false));

				ExecutorService httpExecutor = Executors.newCachedThreadPool(daemonFactory());
				server.setExecutor(httpExecutor);
				server.start();

				httpServer = server;
				executor = httpExecutor;
				running = true;
				s2eEndpointsActive = true;
				runningBindAddress = bindHost;
				runningPort = port;

				ArenaOfNations.LOGGER.info(
						"StreamToEarn HTTP bridge started on http://{}:{} (gift/chat only; overlay is on separate port)",
						bindHost,
						port);
			} catch (Exception e) {
				if (server != null) {
					try {
						server.stop(0);
					} catch (Exception stopError) {
						ArenaOfNations.LOGGER.debug("Failed to stop partially started HTTP bridge", stopError);
					}
				}
				running = false;
				s2eEndpointsActive = false;
				httpServer = null;
				shutdownExecutorQuietly();
				ArenaOfNations.LOGGER.error(
						"Failed to start StreamToEarn HTTP bridge on {}:{} — mod continues without S2E bridge",
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
			s2eEndpointsActive = false;
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
				sendJson(exchange, 200, jsonAccepted(true, result.reason()), null);
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
			ArenaStreamToEarnCommands.recordHttpHit(chat);

			String method = exchange.getRequestMethod();
			if ("OPTIONS".equalsIgnoreCase(method)) {
				Headers headers = exchange.getResponseHeaders();
				headers.set("Access-Control-Allow-Origin", "*");
				headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
				headers.set("Access-Control-Allow-Headers", "Content-Type, X-Arena-Token");
				headers.set("Access-Control-Max-Age", "86400");
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
				return;
			}

			if (!"POST".equalsIgnoreCase(method)) {
				rejectHttp(exchange, 405, "method_not_allowed", "POST");
				return;
			}

			String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
			if (contentLengthHeader != null) {
				try {
					long contentLength = Long.parseLong(contentLengthHeader.trim());
					if (contentLength > MAX_COMPAT_FULL_BODY_BYTES) {
						drainAndClose(exchange.getRequestBody());
						ArenaStreamToEarnCommands.recordLastHttpBody("(too_large)");
						rejectHttp(exchange, 413, "payload_too_large", null);
						return;
					}
				} catch (NumberFormatException ignored) {
					// Fall through to bounded body read.
				}
			}

			byte[] bodyBytes = readBodyLimited(exchange.getRequestBody(), MAX_COMPAT_FULL_BODY_BYTES);
			if (bodyBytes == null) {
				ArenaStreamToEarnCommands.recordLastHttpBody("(too_large)");
				rejectHttp(exchange, 413, "payload_too_large", null);
				return;
			}

			bodyBytes = stripUtf8Bom(bodyBytes);
			String bodyText = stripBomChar(new String(bodyBytes, StandardCharsets.UTF_8));
			ArenaStreamToEarnCommands.recordLastHttpBody(bodyText);
			int firstNonWs = indexOfFirstNonWhitespace(bodyText);
			if (firstNonWs >= 0 && bodyText.charAt(firstNonWs) == '{') {
				handleJsonBodyAuth(exchange, chat, bodyText.substring(firstNonWs));
			} else {
				handlePlainTextBodyAuth(exchange, chat, bodyBytes);
			}
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("StreamToEarn HTTP body-auth endpoint failed", e);
			rejectHttp(exchange, 500, "internal_error", null);
		}
	}

	private static void handlePlainTextBodyAuth(HttpExchange exchange, boolean chat, byte[] bodyBytes)
			throws IOException {
		int sepIndex = indexOfSeparator(bodyBytes);
		if (sepIndex < 0) {
			rejectHttp(exchange, 400, "missing_separator", null);
			return;
		}

		int tokenLen = sepIndex;
		int payloadOffset = sepIndex + SEPARATOR.length();
		int payloadLen = bodyBytes.length - payloadOffset;

		if (tokenLen > MAX_COMPAT_TOKEN_BYTES || payloadLen > MAX_COMPAT_PAYLOAD_BYTES) {
			rejectHttp(exchange, 413, "payload_too_large", null);
			return;
		}

		byte[] tokenBytes = new byte[tokenLen];
		System.arraycopy(bodyBytes, 0, tokenBytes, 0, tokenLen);
		byte[] payloadBytes = new byte[payloadLen];
		if (payloadLen > 0) {
			System.arraycopy(bodyBytes, payloadOffset, payloadBytes, 0, payloadLen);
		}

		if (tokenLen == 0 || !tokenMatches(tokenBytes)) {
			rejectHttp(exchange, 401, "unauthorized", null);
			return;
		}

		String payload = new String(payloadBytes, StandardCharsets.UTF_8);
		respondAcceptResult(exchange, chat, payload);
	}

	private static void handleJsonBodyAuth(HttpExchange exchange, boolean chat, String bodyText) throws IOException {
		JsonObject obj;
		try {
			JsonElement root = parseCompatJsonRoot(bodyText);
			if (!root.isJsonObject()) {
				rejectHttp(exchange, 400, "malformed_json", null);
				return;
			}
			obj = root.getAsJsonObject();
		} catch (JsonParseException e) {
			rejectHttp(exchange, 400, "malformed_json", null);
			return;
		}

		String token = readJsonStringField(obj, "token", true);
		if (token == null) {
			rejectHttp(exchange, 400, "missing_field", null);
			return;
		}
		byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
		if (tokenBytes.length > MAX_COMPAT_TOKEN_BYTES) {
			rejectHttp(exchange, 413, "payload_too_large", null);
			return;
		}
		if (token.isEmpty() || !tokenMatches(tokenBytes)) {
			rejectHttp(exchange, 401, "unauthorized", null);
			return;
		}

		String viewerId = readJsonStringField(obj, "viewerId", true);
		if (viewerId == null || viewerId.isEmpty() || "{uniqueid}".equals(viewerId)) {
			viewerId = "s2e_play";
		}
		if (containsPayloadSeparator(viewerId)) {
			rejectHttp(exchange, 400, "invalid_field", null);
			return;
		}

		String payload;
		if (chat) {
			String message = readJsonStringField(obj, "message", true);
			if (message == null || message.isEmpty() || "{comment}".equals(message)) {
				message = "ru";
			}
			if (containsPayloadSeparator(message)) {
				rejectHttp(exchange, 400, "invalid_field", null);
				return;
			}
			payload = viewerId + SEPARATOR + message;
		} else {
			Integer coins = readJsonCoinsField(obj);
			if (coins == null) {
				coins = 1;
			}
			String eventId = readJsonStringField(obj, "eventId", false);
			if (eventId != null && !eventId.isEmpty() && containsPayloadSeparator(eventId)) {
				rejectHttp(exchange, 400, "invalid_field", null);
				return;
			}
			if (eventId != null && !eventId.isEmpty()) {
				payload = viewerId + SEPARATOR + coins + SEPARATOR + eventId;
			} else {
				payload = viewerId + SEPARATOR + coins;
			}
		}

		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
		if (payloadBytes.length > MAX_COMPAT_PAYLOAD_BYTES) {
			rejectHttp(exchange, 413, "payload_too_large", null);
			return;
		}

		respondAcceptResult(exchange, chat, payload);
	}

	private static void rejectHttp(HttpExchange exchange, int code, String reason, String allowMethod)
			throws IOException {
		ArenaStreamToEarnCommands.recordIngressReject(reason);
		if (code == 405) {
			sendJson(exchange, code, "{\"accepted\":false,\"reason\":\"method_not_allowed\"}", allowMethod);
		} else if (code == 413) {
			sendJson(exchange, code, "{\"accepted\":false,\"reason\":\"payload_too_large\"}", null);
		} else if (code == 401) {
			sendJson(exchange, code, "{\"accepted\":false,\"reason\":\"unauthorized\"}", null);
		} else if (code == 500) {
			sendJson(exchange, code, "{\"accepted\":false,\"reason\":\"internal_error\"}", null);
		} else {
			sendJson(exchange, code, jsonAccepted(false, reason), null);
		}
	}

	static byte[] stripUtf8Bom(byte[] body) {
		if (body != null && body.length >= 3
				&& (body[0] & 0xFF) == 0xEF
				&& (body[1] & 0xFF) == 0xBB
				&& (body[2] & 0xFF) == 0xBF) {
			return Arrays.copyOfRange(body, 3, body.length);
		}
		return body;
	}

	static String stripBomChar(String text) {
		if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
			return text.substring(1);
		}
		return text;
	}

	/**
	 * Parses StreamToEarn JSON bodies, including common UI quirks:
	 * <ul>
	 *   <li>unquoted placeholders like {@code "coins": {coins}} (invalid JSON until quoted)</li>
	 *   <li>double-encoded JSON string from Electron httpBridge</li>
	 * </ul>
	 */
	static JsonElement parseCompatJsonRoot(String bodyText) {
		String normalized = quoteUnquotedStreamToEarnPlaceholders(bodyText);
		JsonElement root = JsonParser.parseString(normalized);
		for (int depth = 0; depth < 2; depth++) {
			if (!root.isJsonPrimitive() || !root.getAsJsonPrimitive().isString()) {
				break;
			}
			String inner = root.getAsString().trim();
			if (inner.isEmpty()) {
				break;
			}
			char first = inner.charAt(0);
			if (first != '{' && first != '"') {
				break;
			}
			root = JsonParser.parseString(quoteUnquotedStreamToEarnPlaceholders(inner));
		}
		return root;
	}

	/**
	 * Turns {@code "coins": {coins}} into {@code "coins": "{coins}"} so Gson can parse the body.
	 */
	static String quoteUnquotedStreamToEarnPlaceholders(String bodyText) {
		if (bodyText == null || bodyText.isEmpty()) {
			return bodyText;
		}
		return bodyText.replaceAll(
				"(:\\s*)\\{([A-Za-z][A-Za-z0-9_]*)\\}(\\s*[,}\\]])",
				"$1\"{$2}\"$3");
	}

	private static boolean containsPayloadSeparator(String value) {
		return value != null && value.contains(SEPARATOR);
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
		if (primitive.isString()) {
			return primitive.getAsString();
		}
		// Some S2E Play payloads may send numeric ids; coerce to string.
		if (primitive.isNumber()) {
			return primitive.getAsString();
		}
		return null;
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
			// StreamToEarn Play often substitutes an empty string when no live gift.
			return 1;
		}
		// StreamToEarn Play without a live gift leaves placeholders unsubstituted.
		if (raw.length() >= 3 && raw.charAt(0) == '{' && raw.charAt(raw.length() - 1) == '}') {
			return 1;
		}
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException e) {
			try {
				double asDouble = Double.parseDouble(raw);
				if (asDouble >= 1.0 && asDouble <= Integer.MAX_VALUE && asDouble == Math.rint(asDouble)) {
					return (int) asDouble;
				}
			} catch (NumberFormatException ignored) {
				// fall through
			}
			return null;
		}
	}

	private static void respondAcceptResult(HttpExchange exchange, boolean chat, String payload) throws IOException {
		ArenaStreamToEarnCommands.AcceptResult result = chat
				? ArenaStreamToEarnCommands.acceptChatPayload(payload)
				: ArenaStreamToEarnCommands.acceptGiftPayload(payload);

		if (result.accepted()) {
			// StreamToEarn httpBridge treats non-200 as failure toast; 202 looked like an error in UI.
			sendJson(exchange, 200, jsonAccepted(true, result.reason()), null);
		} else {
			sendJson(exchange, 400, jsonAccepted(false, result.reason()), null);
		}
	}

	private static int indexOfFirstNonWhitespace(String text) {
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '\uFEFF') {
				continue;
			}
			if (!Character.isWhitespace(ch)) {
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
		// Include ok/success for StreamToEarn clients that check those fields instead of accepted.
		return "{\"accepted\":" + accepted
				+ ",\"ok\":" + accepted
				+ ",\"success\":" + accepted
				+ ",\"reason\":\"" + escapeJson(safeReason) + "\"}";
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
		headers.set("Access-Control-Allow-Origin", "*");
		headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		headers.set("Access-Control-Allow-Headers", "Content-Type, X-Arena-Token");
		if (allowMethod != null) {
			headers.set("Allow", allowMethod);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
