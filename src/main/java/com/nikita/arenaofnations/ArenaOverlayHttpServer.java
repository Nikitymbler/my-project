package com.nikita.arenaofnations;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Loopback overlay-only HTTPS server (bind {@code 127.0.0.1:8766}, optional {@code ::1},
 * primary URL hostname {@code localhost}).
 * Whitelist: TikTok/desktop overlay assets, overlay state, health.
 * Never registers StreamToEarn gift/chat endpoints — those stay on HTTP port 8765.
 */
public final class ArenaOverlayHttpServer {
	private static final String SAFE_BIND_HOST = "127.0.0.1";
	private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);
	private static final Object LOCK = new Object();
	private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(0);
	private static final AtomicInteger ACTIVE_THREADS = new AtomicInteger(0);

	private static HttpServer httpServer;
	private static HttpServer ipv6HttpServer;
	private static ExecutorService executor;
	private static volatile boolean running;
	private static volatile boolean runningHttps;
	private static volatile String runningBind = SAFE_BIND_HOST;
	private static volatile int runningPort = 8766;
	private static volatile String runningHostname = ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME;
	private static volatile String lastStartError = "";
	private static volatile String lastStartResult = "";
	private static volatile boolean startErrorLogged;

	private static volatile net.minecraft.server.MinecraftServer activeServer;

	private ArenaOverlayHttpServer() {
	}

	public static void register() {
		if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeServer = server;
			start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			stop();
			activeServer = null;
		});
	}

	public static net.minecraft.server.MinecraftServer getActiveServer() {
		return activeServer;
	}

	public static boolean isRunning() {
		return running;
	}

	public static boolean isRunningHttps() {
		return running && runningHttps;
	}

	public static String getBindAddress() {
		return runningBind;
	}

	public static int getRunningPort() {
		return runningPort;
	}

	public static String getRunningHostname() {
		return runningHostname;
	}

	public static int getInstanceCount() {
		return INSTANCE_COUNT.get();
	}

	public static int getActiveThreadEstimate() {
		return ACTIVE_THREADS.get();
	}

	public static String getLastStartError() {
		return lastStartError == null ? "" : lastStartError;
	}

	/** Last start outcome: started | alreadyRunning | disabled | failed | notConfigured. */
	public static String getLastStartResult() {
		return lastStartResult == null ? "" : lastStartResult;
	}

	public static String getLocalTikTokUrl() {
		return publicBaseUrl(ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME) + "/overlay/tiktok";
	}

	public static String getLocalPreviewUrl() {
		return getChromaOverlayUrl() + "&preview=1";
	}

	public static String getChromaOverlayUrl() {
		return getLocalTikTokUrl() + "?background=chroma";
	}

	public static String getTransparentOverlayUrl() {
		return getLocalTikTokUrl() + "?background=transparent";
	}

	public static String getLegacyAliasUrl() {
		return publicBaseUrl(ArenaOverlayHttpsMaterial.LEGACY_HOSTNAME) + "/overlay/tiktok";
	}

	public static String getLocalHealthUrl() {
		return publicBaseUrl(ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME) + "/arena/health";
	}

	public static String getLocalStateUrl() {
		return publicBaseUrl(ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME) + "/arena/overlay-state";
	}

	private static String publicBaseUrl(String hostname) {
		String host = hostname == null || hostname.isBlank()
				? ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME
				: hostname.trim();
		String scheme = runningHttps || ArenaConfig.get().isOverlayHttpsEnabled() ? "https" : "http";
		return scheme + "://" + host + ":" + runningPort;
	}

	public static void restart() {
		stop();
		start();
	}

	/** Test hook: plain HTTP (legacy unit tests / whitelist). */
	public static void startForTest(String bindHost, int port) throws Exception {
		synchronized (LOCK) {
			if (running) {
				lastStartResult = "alreadyRunning";
				return;
			}
			startInternalHttp(bindHost, port);
			lastStartResult = "started";
		}
	}

	/** Test hook: HTTPS with provided SSLContext. */
	public static void startHttpsForTest(String bindHost, int port, String hostname, SSLContext sslContext)
			throws Exception {
		synchronized (LOCK) {
			if (running) {
				lastStartResult = "alreadyRunning";
				return;
			}
			startInternalHttps(bindHost, port, hostname, sslContext);
			lastStartResult = "started";
		}
	}

	/** Test hook: stop without Minecraft lifecycle. */
	public static void stopForTest() {
		stop();
	}

	private static void start() {
		synchronized (LOCK) {
			if (running) {
				lastStartResult = "alreadyRunning";
				return;
			}
			ArenaConfig config = ArenaConfig.get();
			if (!config.isOverlayHttpEnabled()) {
				lastStartResult = "disabled";
				ArenaOfNations.LOGGER.info("Overlay HTTPS server disabled (overlay_http_enabled=false).");
				return;
			}
			String bindHost = resolveSafeBindHost(config.getOverlayHttpBind());
			int port = config.getOverlayHttpPort();
			boolean https = config.isOverlayHttpsEnabled();
			try {
				if (https) {
					if (!ArenaOverlayHttpsMaterial.isHttpsMaterialAvailable()) {
						lastStartResult = "notConfigured";
						lastStartError =
								"No usable localhost/loopback certificate in Windows CurrentUser\\My — run SETUP_LOCAL_OVERLAY_HTTPS.cmd";
						if (!startErrorLogged) {
							startErrorLogged = true;
							ArenaOfNations.LOGGER.error(
									"Overlay HTTPS is enabled but no usable Windows-MY certificate was found for localhost/127.0.0.1. "
											+ "Double-click SETUP_LOCAL_OVERLAY_HTTPS.cmd (accept UAC), then restart Minecraft.");
						}
						return;
					}
					SSLContext sslContext = ArenaOverlayHttpsMaterial.loadSslContext();
					startInternalHttps(bindHost, port, ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME, sslContext);
				} else {
					startInternalHttp(bindHost, port);
				}
				startErrorLogged = false;
				lastStartError = "";
				lastStartResult = "started";
				ArenaOverlayHttpIO.clearLastError();
				ArenaOverlayLayoutConfig.ensureLoaded();
				ArenaOfNations.LOGGER.info(
						"Overlay HTTPS start result=started; primaryUrl={}",
						getLocalTikTokUrl());
			} catch (Exception e) {
				lastStartResult = "failed";
				lastStartError = e.getClass().getSimpleName() + ": " + safeMessage(e);
				if (!startErrorLogged) {
					startErrorLogged = true;
					ArenaOfNations.LOGGER.error(
							"Failed to start overlay server on {}:{} — run SETUP_LOCAL_OVERLAY_HTTPS.cmd if HTTPS is not set up. StreamToEarn bridge is independent.",
							bindHost,
							port,
							e);
				}
			}
		}
	}

	private static void startInternalHttp(String bindHost, int port) throws Exception {
		HttpServer server = null;
		try {
			InetSocketAddress address = new InetSocketAddress(bindHost, port);
			server = HttpServer.create(address, 0);
			registerContexts(server);
			ExecutorService httpExecutor = Executors.newCachedThreadPool(daemonFactory());
			server.setExecutor(httpExecutor);
			server.start();
			httpServer = server;
			executor = httpExecutor;
			running = true;
			runningHttps = false;
			runningBind = bindHost;
			runningPort = port;
			runningHostname = bindHost;
			INSTANCE_COUNT.set(1);
			ArenaOfNations.LOGGER.info(
					"Overlay HTTP server started on http://{}:{} (test/legacy; S2E stays on separate port)",
					bindHost,
					port);
		} catch (Exception e) {
			abortPartialStart(server);
			throw e;
		}
	}

	private static void startInternalHttps(String bindHost, int port, String hostname, SSLContext sslContext)
			throws Exception {
		HttpsServer server = null;
		HttpsServer ipv6 = null;
		try {
			InetSocketAddress address = new InetSocketAddress(bindHost, port);
			server = HttpsServer.create(address, 0);
			server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
			registerContexts(server);
			ExecutorService httpExecutor = Executors.newCachedThreadPool(daemonFactory());
			server.setExecutor(httpExecutor);
			server.start();

			// Best-effort IPv6 loopback so Windows "localhost" → ::1 still reaches the overlay.
			try {
				InetSocketAddress v6 = new InetSocketAddress(InetAddress.getByName("::1"), port);
				ipv6 = HttpsServer.create(v6, 0);
				ipv6.setHttpsConfigurator(new HttpsConfigurator(sslContext));
				registerContexts(ipv6);
				ipv6.setExecutor(httpExecutor);
				ipv6.start();
				ipv6HttpServer = ipv6;
				ArenaOfNations.LOGGER.info("Overlay HTTPS also listening on [::1]:{}", port);
			} catch (Exception ipv6Error) {
				ArenaOfNations.LOGGER.debug("Optional ::1 overlay bind skipped: {}", ipv6Error.toString());
				if (ipv6 != null) {
					try {
						ipv6.stop(0);
					} catch (Exception ignored) {
						// ignore
					}
					ipv6HttpServer = null;
				}
			}

			httpServer = server;
			executor = httpExecutor;
			running = true;
			runningHttps = true;
			runningBind = bindHost;
			runningPort = port;
			runningHostname = ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME;
			INSTANCE_COUNT.set(1);
			ArenaOfNations.LOGGER.info(
					"Overlay HTTPS server started on https://{}:{} (bind {}); whitelist only; S2E stays on separate port",
					runningHostname,
					port,
					bindHost);
		} catch (Exception e) {
			abortPartialStart(server);
			abortPartialStart(ipv6);
			throw e;
		}
	}

	private static void registerContexts(HttpServer server) {
		server.createContext("/arena/health", ArenaOverlayHttpIO::handleHealth);
		server.createContext("/arena/overlay-state", ArenaOverlayHttpIO::handleOverlayState);
		server.createContext("/api/arena/state", ArenaOverlayHttpIO::handleOverlayState);
		server.createContext("/overlay/api/layout/reset", ArenaOverlayHttpIO::handleLayoutReset);
		server.createContext("/overlay/api/layout", exchange -> {
			String method = exchange.getRequestMethod();
			if ("GET".equalsIgnoreCase(method)) {
				ArenaOverlayHttpIO.handleLayoutGet(exchange);
			} else if ("POST".equalsIgnoreCase(method)) {
				ArenaOverlayHttpIO.handleLayoutPost(exchange);
			} else {
				ArenaOverlayHttpIO.sendJson(
						exchange,
						405,
						"{\"ok\":false,\"reason\":\"method_not_allowed\"}",
						"GET, POST");
			}
		});
		server.createContext("/overlay/api/stats/reset-round-wins", ArenaOverlayHttpIO::handleStatsResetRoundWins);
		server.createContext("/overlay/api/stats/reset-score-points", ArenaOverlayHttpIO::handleStatsResetScorePoints);
		server.createContext("/overlay/api/stats/reset-fighter-record", ArenaOverlayHttpIO::handleStatsResetFighterRecord);
		server.createContext("/overlay/api/stats/reset-all", ArenaOverlayHttpIO::handleStatsResetAll);
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
		server.createContext("/overlay", ArenaOverlayHttpIO::handleOverlayRoot);
		server.createContext("/overlay/", ArenaOverlayHttpIO::handleOverlayAsset);
		// Explicit 404 for any other /arena/* path (gift/chat/S2E must not be reachable on 8766).
		server.createContext("/arena", exchange -> ArenaOverlayHttpIO.sendJson(
				exchange,
				"POST".equalsIgnoreCase(exchange.getRequestMethod()) ? 405 : 404,
				"{\"ok\":false,\"reason\":\"not_found\"}",
				"GET"));
	}

	private static void abortPartialStart(HttpServer server) {
		if (server != null) {
			try {
				server.stop(0);
			} catch (Exception stopError) {
				ArenaOfNations.LOGGER.debug("Failed to stop partially started overlay server", stopError);
			}
		}
		running = false;
		runningHttps = false;
		httpServer = null;
		INSTANCE_COUNT.set(0);
		shutdownExecutorQuietly();
	}

	private static void stop() {
		synchronized (LOCK) {
			if (ipv6HttpServer != null) {
				try {
					ipv6HttpServer.stop(0);
				} catch (Exception e) {
					ArenaOfNations.LOGGER.warn("Error while stopping overlay IPv6 HTTPS server", e);
				}
				ipv6HttpServer = null;
			}
			if (httpServer != null) {
				try {
					httpServer.stop(0);
				} catch (Exception e) {
					ArenaOfNations.LOGGER.warn("Error while stopping overlay HTTP(S) server", e);
				}
				httpServer = null;
			}
			shutdownExecutorQuietly();
			INSTANCE_COUNT.set(0);
			ACTIVE_THREADS.set(0);
			if (running) {
				running = false;
				runningHttps = false;
				ArenaOfNations.LOGGER.info("Overlay HTTPS server stopped.");
			} else {
				running = false;
				runningHttps = false;
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
			Thread thread = new Thread(() -> {
				ACTIVE_THREADS.incrementAndGet();
				try {
					runnable.run();
				} finally {
					ACTIVE_THREADS.decrementAndGet();
				}
			}, "arena-overlay-https");
			thread.setDaemon(true);
			return thread;
		};
	}

	private static String resolveSafeBindHost(String configured) {
		String value = configured == null ? "" : configured.trim();
		if (!SAFE_BIND_HOST.equals(value)) {
			ArenaOfNations.LOGGER.warn("Unsafe overlay HTTP bind '{}', forcing {}", value, SAFE_BIND_HOST);
			return SAFE_BIND_HOST;
		}
		return value;
	}

	private static String safeMessage(Exception e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			return e.getClass().getSimpleName();
		}
		String lower = message.toLowerCase();
		if (lower.contains("eyj") || lower.contains("token") || lower.contains("password") || lower.contains("private")) {
			return e.getClass().getSimpleName();
		}
		return message.length() > 160 ? message.substring(0, 160) : message;
	}
}
