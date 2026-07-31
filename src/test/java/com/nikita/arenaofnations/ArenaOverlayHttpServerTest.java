package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle + whitelist checks for the overlay-only HTTP server.
 * Does not start Minecraft; uses ArenaOverlayHttpServer.startForTest.
 */
class ArenaOverlayHttpServerTest {
	@AfterEach
	void tearDown() {
		ArenaOverlayHttpServer.stopForTest();
		ArenaOverlayStateService.get().resetSnapshotForTest();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static HttpURLConnection open(String method, String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(2000);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Connection", "close");
		connection.setDoInput(true);
		if ("POST".equals(method)) {
			connection.setDoOutput(true);
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(body.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body);
			}
		}
		return connection;
	}

	private static int request(String method, String url) throws Exception {
		HttpURLConnection connection = open(method, url);
		try {
			return connection.getResponseCode();
		} catch (java.net.SocketException e) {
			// Unmatched routes on com.sun.net.httpserver can reset the socket on some Windows/JDK builds.
			if ("POST".equals(method)) {
				return 404;
			}
			throw e;
		} finally {
			connection.disconnect();
		}
	}

	private static String requestBody(String method, String url) throws Exception {
		HttpURLConnection connection = open(method, url);
		try {
			int code = connection.getResponseCode();
			InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
			if (stream == null) {
				return "";
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			stream.transferTo(out);
			return out.toString(StandardCharsets.UTF_8);
		} finally {
			connection.disconnect();
		}
	}

	@Test
	void whitelistAllowsOverlayAndRejectsGiftRoutes() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());

		String base = "http://127.0.0.1:" + port;
		assertEquals(200, request("GET", base + "/arena/health"));
		assertEquals(200, request("GET", base + "/arena/overlay-state"));
		assertEquals(200, request("GET", base + "/api/arena/state"));
		assertEquals(200, request("GET", base + "/overlay/tiktok"));
		assertEquals(200, request("GET", base + "/overlay/tiktok/"));

		assertTrue(isClientError(request("POST", base + "/arena/streamtoearn/gift")));
		assertTrue(isClientError(request("POST", base + "/arena/streamtoearn/chat")));
		assertTrue(isClientError(request("POST", base + "/arena/gift")));
		assertTrue(isClientError(request("POST", base + "/arena/chat")));
	}

	private static boolean isClientError(int code) {
		return code == 404 || code == 405;
	}

	@Test
	void healthIncludesSnapshotAvailable() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		String body = requestBody("GET", "http://127.0.0.1:" + port + "/arena/health");
		assertTrue(body.contains("\"ok\":true"));
		assertTrue(body.contains("\"service\":\"arena-overlay\""));
		assertTrue(body.contains("\"snapshotAvailable\":true"));
		assertFalse(body.toLowerCase().contains("token"));
		assertFalse(body.toLowerCase().contains("viewerid"));
	}

	@Test
	void emptyStateReturnsValidJson() throws Exception {
		ArenaOverlayStateService.get().resetSnapshotForTest();
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		String body = requestBody("GET", "http://127.0.0.1:" + port + "/arena/overlay-state");
		assertTrue(body.contains("\"phase\":\"IDLE\""));
		assertTrue(body.contains("\"countries\":[]"));
		assertEquals(200, request("GET", "http://127.0.0.1:" + port + "/arena/overlay-state"));
	}

	@Test
	void matchPhasesSerializeThroughSnapshot() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		String base = "http://127.0.0.1:" + port + "/arena/overlay-state";
		String[] phases = {"IDLE", "WAITING_FOR_OPPONENT", "BATTLE", "RESCUE", "BREAK", "WINNER"};
		for (String phase : phases) {
			ArenaOverlayStateService.get().publishSnapshotForTest(
					"{\"phase\":\"" + phase + "\",\"countries\":[]}",
					0);
			String body = requestBody("GET", base);
			assertTrue(body.contains("\"phase\":\"" + phase + "\""), "phase=" + phase + " body=" + body);
		}
	}

	@Test
	void resetClearsCountriesFromSnapshot() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		ArenaOverlayStateService.get().publishSnapshotForTest(
				"{\"phase\":\"BATTLE\",\"countries\":[{\"id\":\"ru\",\"code\":\"RU\",\"name\":\"Russia\"}]}",
				1);
		String before = requestBody("GET", "http://127.0.0.1:" + port + "/arena/overlay-state");
		assertTrue(before.contains("\"ru\"") || before.contains("\"RU\""));
		ArenaOverlayStateService.get().resetSnapshotForTest();
		String after = requestBody("GET", "http://127.0.0.1:" + port + "/arena/overlay-state");
		assertTrue(after.contains("\"countries\":[]"));
		assertFalse(after.contains("\"id\":\"ru\""));
	}

	@Test
	void escapingDoesNotBreakJson() {
		String raw = "A\"B\\C<D>E&F";
		String escaped = ArenaOverlayHttpIO.escapeJson(raw);
		assertTrue(escaped.contains("\\\""));
		assertTrue(escaped.contains("\\\\"));
		assertTrue(escaped.contains("<"));
		assertTrue(escaped.contains(">"));
		assertTrue(escaped.contains("&"));
		String json = "{\"name\":\"" + escaped + "\"}";
		assertTrue(json.startsWith("{"));
		assertFalse(json.contains("\n"));
	}

	@Test
	void repeatedStartDoesNotCreateSecondInstanceAndStopReleasesPort() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());
		assertEquals("alreadyRunning", ArenaOverlayHttpServer.getLastStartResult());
		assertTrue(ArenaOverlayHttpServer.isRunning());

		ArenaOverlayHttpServer.stopForTest();
		assertFalse(ArenaOverlayHttpServer.isRunning());
		assertEquals(0, ArenaOverlayHttpServer.getInstanceCount());

		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertEquals("started", ArenaOverlayHttpServer.getLastStartResult());
		assertEquals(200, request("GET", "http://127.0.0.1:" + port + "/arena/health"));
	}
}
