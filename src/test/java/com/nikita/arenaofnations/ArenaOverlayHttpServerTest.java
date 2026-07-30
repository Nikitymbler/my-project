package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static int request(String method, String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(2000);
		connection.setRequestMethod(method);
		connection.setDoInput(true);
		if ("POST".equals(method)) {
			connection.setDoOutput(true);
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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

	@Test
	void whitelistAllowsOverlayAndRejectsGiftRoutes() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());

		String base = "http://127.0.0.1:" + port;
		assertEquals(200, request("GET", base + "/arena/health"));
		// State endpoint may 500 without Minecraft overlay service bootstrap — accept 200 or 500,
		// but must not be 404 (route is registered).
		int stateCode = request("GET", base + "/arena/overlay-state");
		assertTrue(stateCode == 200 || stateCode == 500, "state code=" + stateCode);
		int apiCode = request("GET", base + "/api/arena/state");
		assertTrue(apiCode == 200 || apiCode == 500, "api code=" + apiCode);
		int tiktokCode = request("GET", base + "/overlay/tiktok");
		assertTrue(tiktokCode == 200 || tiktokCode == 404, "tiktok code=" + tiktokCode);

		assertEquals(404, request("POST", base + "/arena/streamtoearn/gift"));
		assertEquals(404, request("POST", base + "/arena/streamtoearn/chat"));
		assertEquals(404, request("POST", base + "/arena/gift"));
	}

	@Test
	void repeatedStartDoesNotCreateSecondInstanceAndStopReleasesPort() throws Exception {
		int port = freePort();
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());
		assertTrue(ArenaOverlayHttpServer.isRunning());

		ArenaOverlayHttpServer.stopForTest();
		assertFalse(ArenaOverlayHttpServer.isRunning());
		assertEquals(0, ArenaOverlayHttpServer.getInstanceCount());

		ArenaOverlayHttpServer.startForTest("127.0.0.1", port);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertEquals(200, request("GET", "http://127.0.0.1:" + port + "/arena/health"));
	}
}
