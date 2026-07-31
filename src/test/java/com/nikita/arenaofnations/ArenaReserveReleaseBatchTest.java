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
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArenaReserveReleaseBatchTest {
	private Path tempConfig;

	@BeforeEach
	void setUp() throws Exception {
		tempConfig = Files.createTempFile("arena-config-", ".properties");
		Files.deleteIfExists(tempConfig);
		System.setProperty("arena.config.path", tempConfig.toString());
		writeMinimalConfig(10);
		ArenaConfig.load();
	}

	@AfterEach
	void tearDown() throws Exception {
		ArenaOverlayHttpServer.stopForTest();
		System.clearProperty("arena.config.path");
		Files.deleteIfExists(tempConfig);
		Files.deleteIfExists(Path.of(tempConfig.toString() + ".tmp"));
		writeMinimalConfig(10);
		System.setProperty("arena.config.path", tempConfig.toString());
		ArenaConfig.load();
		System.clearProperty("arena.config.path");
		Files.deleteIfExists(tempConfig);
	}

	private void writeMinimalConfig(int waveSize) throws Exception {
		Properties properties = new Properties();
		properties.setProperty("reserve_wave_size", Integer.toString(waveSize));
		properties.setProperty("reserve_wave_interval_ticks", "40");
		try (OutputStream out = Files.newOutputStream(tempConfig)) {
			properties.store(out, "test");
		}
	}

	@Test
	void actualReleaseFormulaCasesAtoF() {
		assertEquals(1, ArenaReserveReleaseMath.computeActualRelease(1, 50, 20));
		assertEquals(10, ArenaReserveReleaseMath.computeActualRelease(10, 50, 20));
		assertEquals(100, ArenaReserveReleaseMath.computeActualRelease(100, 200, 100));
		assertEquals(3, ArenaReserveReleaseMath.computeActualRelease(100, 200, 3));
		assertEquals(7, ArenaReserveReleaseMath.computeActualRelease(100, 7, 50));
		assertEquals(0, ArenaReserveReleaseMath.computeActualRelease(100, 500, 0));
	}

	@Test
	void availableSlotsAndPerCountryCaps() {
		assertEquals(5, ArenaReserveReleaseMath.availableActiveSlots(10, 5));
		assertEquals(0, ArenaReserveReleaseMath.availableActiveSlots(5, 5));
		assertEquals(Integer.MAX_VALUE, ArenaReserveReleaseMath.availableActiveSlots(Integer.MAX_VALUE, 999));

		// Case I: same batch, different free slots per country.
		assertEquals(5, ArenaReserveReleaseMath.computeActualRelease(10, 100, 5));
		assertEquals(2, ArenaReserveReleaseMath.computeActualRelease(10, 100, 2));
	}

	@Test
	void liveBatchChangeDoesNotRequireTimerReset() {
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		assertTrue(settings.apply(10, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(10, settings.getReserveReleaseBatch());

		// Simulate mid-battle change: next wave reads live value without resetting interval.
		int interval = settings.getReserveReleaseIntervalTicks();
		assertEquals(40, interval);
		assertTrue(settings.apply(25, ArenaReserveRuntimeSettings.ChangedBy.BROWSER).success());
		assertEquals(25, settings.getReserveReleaseBatch());
		assertEquals(interval, settings.getReserveReleaseIntervalTicks());

		assertTrue(settings.apply(2, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(2, ArenaReserveReleaseMath.computeActualRelease(
				settings.getReserveReleaseBatch(), 50, 20));
	}

	@Test
	void rejectsOutOfRangeWithoutChangingRuntime() {
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		assertTrue(settings.apply(10, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertFalse(settings.apply(0, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(10, settings.getReserveReleaseBatch());
		assertFalse(settings.apply(101, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(10, settings.getReserveReleaseBatch());
	}

	@Test
	void persistenceSurvivesReloadAndMigratesMissingKey() throws Exception {
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		assertTrue(settings.apply(37, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(37, settings.getReserveReleaseBatch());
		assertTrue(Files.readString(tempConfig).contains("reserve_wave_size=37"));

		ArenaConfig.load();
		assertEquals(37, ArenaConfig.get().getReserveWaveSize());
		assertEquals(37, settings.getReserveReleaseBatch());

		// Old config without the field → default 10.
		Properties old = new Properties();
		old.setProperty("battle_seconds", "600");
		try (OutputStream out = Files.newOutputStream(tempConfig)) {
			old.store(out, "legacy");
		}
		ArenaConfig.load();
		assertEquals(10, ArenaConfig.get().getReserveWaveSize());
		assertEquals(10, settings.getReserveReleaseBatch());

		assertTrue(settings.apply(12, ArenaReserveRuntimeSettings.ChangedBy.CONFIG).success());
		assertTrue(Files.readString(tempConfig).contains("reserve_wave_size=12"));
		assertTrue(Files.readString(tempConfig).contains("battle_seconds=600"));
	}

	@Test
	void parseApiBodyRejectsInvalidValues() {
		assertEquals(1, ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":1}"));
		assertEquals(25, ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":25}"));
		assertEquals(100, ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":100}"));

		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":0}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":101}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":-5}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":1.5}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":\"10\"}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{\"reserveReleaseBatch\":null}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("{}"));
		assertThrows(IllegalArgumentException.class,
				() -> ArenaOverlayHttpIO.parseReserveReleaseBatch("not-json"));
	}

	@Test
	void httpGetAndPostReserveSettings() throws Exception {
		int port = freePort();
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
				new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/overlay/api/runtime/reserve-settings", exchange -> {
			String method = exchange.getRequestMethod();
			if ("GET".equalsIgnoreCase(method)) {
				ArenaOverlayHttpIO.handleReserveSettingsGet(exchange);
			} else if ("POST".equalsIgnoreCase(method)) {
				ArenaOverlayHttpIO.handleReserveSettingsPost(exchange);
			} else {
				ArenaOverlayHttpIO.sendJson(
						exchange,
						405,
						"{\"success\":false,\"message\":\"method_not_allowed\"}",
						"GET, POST");
			}
		});
		server.start();
		try {
			ArenaReserveRuntimeSettings.get().apply(10, ArenaReserveRuntimeSettings.ChangedBy.COMMAND);

			String getBody = http(port, "GET", null);
			assertTrue(getBody.contains("\"success\":true"));
			assertTrue(getBody.contains("\"reserveReleaseBatch\":10"));
			assertTrue(getBody.contains("\"minimum\":1"));
			assertTrue(getBody.contains("\"maximum\":100"));
			assertTrue(getBody.contains("\"reserveReleaseIntervalTicks\":40"));
			assertTrue(getBody.contains("\"liveEditable\":true"));

			String postOk = http(port, "POST", "{\"reserveReleaseBatch\":25}");
			assertTrue(postOk.contains("\"success\":true"));
			assertTrue(postOk.contains("\"reserveReleaseBatch\":25"));
			assertEquals(25, ArenaReserveRuntimeSettings.get().getReserveReleaseBatch());

			String getAfter = http(port, "GET", null);
			assertTrue(getAfter.contains("\"reserveReleaseBatch\":25"));

			int before = ArenaReserveRuntimeSettings.get().getReserveReleaseBatch();
			String postBad = http(port, "POST", "{\"reserveReleaseBatch\":101}");
			assertTrue(postBad.contains("\"success\":false"));
			assertEquals(before, ArenaReserveRuntimeSettings.get().getReserveReleaseBatch());

			HttpURLConnection put = (HttpURLConnection) URI.create(
					"http://127.0.0.1:" + port + "/overlay/api/runtime/reserve-settings").toURL().openConnection();
			put.setRequestMethod("PUT");
			put.connect();
			assertEquals(405, put.getResponseCode());
			put.disconnect();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void overlayAssetsContainReserveEditSection() throws Exception {
		String html = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/index.html"),
				StandardCharsets.UTF_8);
		assertTrue(html.contains("РЕЗЕРВ"));
		assertTrue(html.contains("ВЫПУСК ЗА ОДНУ ВОЛНУ"));
		assertTrue(html.contains("btn-reserve-batch-apply"));
		assertTrue(html.contains("ПРИМЕНИТЬ СЕЙЧАС"));
		assertTrue(html.contains("reserve-batch-input"));

		String js = Files.readString(
				Path.of("src/main/resources/assets/arena_of_nations/overlay/tiktok/tiktok.js"),
				StandardCharsets.UTF_8);
		assertTrue(js.contains("/overlay/api/runtime/reserve-settings"));
		assertTrue(js.contains("setupReserveBatchControls"));
		assertTrue(js.contains("runtimeSettings"));
		assertTrue(js.contains("editMode"));

		String server = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaOverlayHttpServer.java"),
				StandardCharsets.UTF_8);
		assertTrue(server.contains("/overlay/api/runtime/reserve-settings"));

		String commands = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchCommands.java"),
				StandardCharsets.UTF_8);
		assertTrue(commands.contains("arena_reserve_batch"));
		assertTrue(commands.contains("arena_config_status"));

		String scheduler = Files.readString(
				Path.of("src/main/java/com/nikita/arenaofnations/ArenaMatchManager.java"),
				StandardCharsets.UTF_8);
		assertTrue(scheduler.contains("getReserveReleaseBatch()"));
		assertTrue(scheduler.contains("computeActualRelease"));
		assertFalse(scheduler.contains("config.getReserveWaveSize()"));
	}

	@Test
	void commandAndBrowserShareSameRuntimeValue() {
		ArenaReserveRuntimeSettings settings = ArenaReserveRuntimeSettings.get();
		assertTrue(settings.apply(42, ArenaReserveRuntimeSettings.ChangedBy.COMMAND).success());
		assertEquals(42, settings.getReserveReleaseBatch());
		assertTrue(settings.apply(55, ArenaReserveRuntimeSettings.ChangedBy.BROWSER).success());
		assertEquals(55, settings.getReserveReleaseBatch());
		assertEquals(55, ArenaConfig.get().getReserveWaveSize());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static String http(int port, String method, String body) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) URI.create(
				"http://127.0.0.1:" + port + "/overlay/api/runtime/reserve-settings").toURL().openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(3000);
		conn.setReadTimeout(3000);
		if (body != null) {
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			try (OutputStream out = conn.getOutputStream()) {
				out.write(bytes);
			}
		}
		int code = conn.getResponseCode();
		InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
		if (stream == null) {
			stream = conn.getInputStream();
		}
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		stream.transferTo(buf);
		conn.disconnect();
		return buf.toString(StandardCharsets.UTF_8);
	}
}
