package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArenaOverlayLayoutConfigTest {
	private Path tempConfig;

	@BeforeEach
	void setUp() throws Exception {
		tempConfig = Files.createTempFile("arena-overlay-layout-", ".json");
		Files.deleteIfExists(tempConfig);
		System.setProperty("arena.overlay.layout.config", tempConfig.toString());
		ArenaOverlayLayoutConfig.resetForTest();
	}

	@AfterEach
	void tearDown() throws Exception {
		ArenaOverlayHttpServer.stopForTest();
		ArenaOverlayLayoutConfig.resetForTest();
		System.clearProperty("arena.overlay.layout.config");
		Files.deleteIfExists(tempConfig);
		Files.deleteIfExists(Path.of(tempConfig.toString() + ".tmp"));
	}

	@Test
	void parseClampsAndIgnoresUnknownFields() {
		ArenaOverlayLayoutConfig.LayoutState state = ArenaOverlayLayoutConfig.parse("""
				{"version":2,"battle":{"xRatio":1.5,"yRatio":-0.2,"visible":false,"extra":1},
				"top5":{"xRatio":0.5,"yRatio":0.25},"unknown":true}
				""");
		assertEquals(0.0, state.battle().yRatio(), 0.0001);
		assertEquals(1.0, state.battle().xRatio(), 0.0001);
		assertFalse(state.battle().visible());
		assertEquals(0.5, state.top5().xRatio(), 0.0001);
		assertTrue(state.top5().visible());
	}

	@Test
	void invalidJsonRejected() {
		assertThrows(IllegalArgumentException.class, () -> ArenaOverlayLayoutConfig.parse("{not-json"));
		assertThrows(IllegalArgumentException.class, () -> ArenaOverlayLayoutConfig.parse(""));
	}

	@Test
	void atomicSaveAndResetPersistFile() throws Exception {
		assertFalse(Files.isRegularFile(tempConfig));
		ArenaOverlayLayoutConfig.LayoutState saved = ArenaOverlayLayoutConfig.save(
				new ArenaOverlayLayoutConfig.LayoutState(
						3,
						new ArenaOverlayLayoutConfig.ModuleLayout(0.11, 0.22, true, 1.0),
						new ArenaOverlayLayoutConfig.ModuleLayout(0.33, 0.44, false, 1.0),
						new ArenaOverlayLayoutConfig.ModuleLayout(0.84, 0.08, true, 1.2),
						true));
		assertTrue(Files.isRegularFile(tempConfig));
		assertTrue(ArenaOverlayLayoutConfig.currentJson().contains("\"configFileExists\":true"));
		assertEquals(0.11, saved.battle().xRatio(), 0.0001);
		assertEquals(0.84, saved.record().xRatio(), 0.0001);
		assertEquals(1.2, saved.record().scale(), 0.0001);
		assertTrue(ArenaOverlayLayoutConfig.legacyLocalStorageMigrated());

		ArenaOverlayLayoutConfig.resetForTest();
		System.setProperty("arena.overlay.layout.config", tempConfig.toString());
		ArenaOverlayLayoutConfig.ensureLoaded();
		assertEquals(0.11, ArenaOverlayLayoutConfig.current().battle().xRatio(), 0.0001);
		assertFalse(ArenaOverlayLayoutConfig.current().top5().visible());
		assertEquals(1.2, ArenaOverlayLayoutConfig.current().record().scale(), 0.0001);

		ArenaOverlayLayoutConfig.LayoutState reset = ArenaOverlayLayoutConfig.resetToDefaults();
		assertEquals(ArenaOverlayLayoutConfig.DEFAULT_BATTLE_X, reset.battle().xRatio(), 0.0001);
		assertEquals(ArenaOverlayLayoutConfig.DEFAULT_TOP5_Y, reset.top5().yRatio(), 0.0001);
		assertEquals(ArenaOverlayLayoutConfig.DEFAULT_RECORD_X, reset.record().xRatio(), 0.0001);
		assertTrue(reset.top5().visible());
	}

	@Test
	void httpLayoutGetPostResetRoundTrip() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		String base = "http://127.0.0.1:" + port;

		String getBody = requestBody("GET", base + "/overlay/api/layout");
		assertTrue(getBody.contains("\"version\":3"));
		assertTrue(getBody.contains("\"xRatio\""));
		assertTrue(getBody.contains("configFileExists"));
		assertTrue(getBody.contains("\"record\""));

		String postBody = requestBodyWithPayload(
				"POST",
				base + "/overlay/api/layout",
				"{\"version\":3,\"battle\":{\"xRatio\":0.12,\"yRatio\":0.34,\"visible\":true,\"scale\":1},"
						+ "\"top5\":{\"xRatio\":0.56,\"yRatio\":0.78,\"visible\":false,\"scale\":1},"
						+ "\"record\":{\"xRatio\":0.84,\"yRatio\":0.08,\"visible\":true,\"scale\":1},"
						+ "\"migratedFromLocalStorage\":true}");
		assertTrue(postBody.contains("0.120000") || postBody.contains("\"xRatio\":0.12"));
		assertTrue(postBody.contains("\"visible\":false"));
		assertTrue(postBody.contains("\"record\""));
		assertTrue(Files.isRegularFile(tempConfig));

		assertEquals(400, requestCodeWithPayload("POST", base + "/overlay/api/layout", "{bad"));

		assertEquals(400, requestCodeWithPayload(
				"POST",
				base + "/overlay/api/stats/reset-all",
				"{bad"));
		assertEquals(400, requestCodeWithPayload(
				"POST",
				base + "/overlay/api/stats/reset-round-wins",
				"{\"confirm\":false}"));

		String resetBody = requestBodyWithPayload("POST", base + "/overlay/api/layout/reset", "{}");
		assertTrue(resetBody.contains(String.format(java.util.Locale.ROOT, "%.6f", ArenaOverlayLayoutConfig.DEFAULT_BATTLE_X))
				|| resetBody.contains("\"xRatio\":0.04"));
		assertTrue(resetBody.contains("\"visible\":true"));
		assertTrue(resetBody.contains("\"record\""));
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static String requestBody(String method, String url) throws Exception {
		return requestBodyWithPayload(method, url, null);
	}

	private static int requestCodeWithPayload(String method, String url, String payload) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(2000);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Connection", "close");
		connection.setDoInput(true);
		if (payload != null) {
			byte[] body = payload.getBytes(StandardCharsets.UTF_8);
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(body.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body);
			}
		}
		try {
			return connection.getResponseCode();
		} finally {
			connection.disconnect();
		}
	}

	private static String requestBodyWithPayload(String method, String url, String payload) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(2000);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Connection", "close");
		connection.setDoInput(true);
		if (payload != null) {
			byte[] body = payload.getBytes(StandardCharsets.UTF_8);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setFixedLengthStreamingMode(body.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body);
			}
		}
		try {
			int code = connection.getResponseCode();
			InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
			if (stream == null) {
				return "";
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			stream.transferTo(out);
			String text = out.toString(StandardCharsets.UTF_8);
			assertTrue(code == 200 || code == 400, "unexpected code=" + code + " body=" + text);
			return text;
		} finally {
			connection.disconnect();
		}
	}
}
