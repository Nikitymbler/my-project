package com.nikita.arenaofnations;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Loopback overlay-only HTTP server (default {@code 127.0.0.1:8766}).
 * Whitelist: TikTok/desktop overlay assets, overlay state, health.
 * Never registers StreamToEarn gift/chat endpoints — those stay on port 8765.
 */
public final class ArenaOverlayHttpServer {
	private static final String SAFE_BIND_HOST = "127.0.0.1";
	private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);
	private static final Object LOCK = new Object();
	private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(0);
	private static final AtomicInteger ACTIVE_THREADS = new AtomicInteger(0);

	private static HttpServer httpServer;
	private static ExecutorService executor;
	private static volatile boolean running;
	private static volatile String runningBind = SAFE_BIND_HOST;
	private static volatile int runningPort = 8766;
	private static volatile String lastStartError = "";
	private static volatile boolean startErrorLogged;

	private ArenaOverlayHttpServer() {
	}

	public static void register() {
		if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> start());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
	}

	public static boolean isRunning() {
		return running;
	}

	public static String getBindAddress() {
		return runningBind;
	}

	public static int getRunningPort() {
		return runningPort;
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

	public static String getLocalTikTokUrl() {
		return "http://" + runningBind + ":" + runningPort + "/overlay/tiktok";
	}

	public static String getLocalHealthUrl() {
		return "http://" + runningBind + ":" + runningPort + "/arena/health";
	}

	public static String getLocalStateUrl() {
		return "http://" + runningBind + ":" + runningPort + "/arena/overlay-state";
	}

	public static void restart() {
		stop();
		start();
	}

	/** Test hook: start on an explicit bind/port without reading Minecraft config. */
	public static void startForTest(String bindHost, int port) throws Exception {
		synchronized (LOCK) {
			if (running) {
				return;
			}
			startInternal(bindHost, port);
		}
	}

	/** Test hook: stop without Minecraft lifecycle. */
	public static void stopForTest() {
		stop();
	}

	private static void start() {
		synchronized (LOCK) {
			if (running) {
				return;
			}
			ArenaConfig config = ArenaConfig.get();
			if (!config.isOverlayHttpEnabled()) {
				ArenaOfNations.LOGGER.info("Overlay HTTP server disabled (overlay_http_enabled=false).");
				return;
			}
			String bindHost = resolveSafeBindHost(config.getOverlayHttpBind());
			int port = config.getOverlayHttpPort();
			try {
				startInternal(bindHost, port);
				startErrorLogged = false;
				lastStartError = "";
				ArenaOverlayHttpIO.clearLastError();
			} catch (Exception e) {
				lastStartError = e.getClass().getSimpleName() + ": " + safeMessage(e);
				if (!startErrorLogged) {
					startErrorLogged = true;
					ArenaOfNations.LOGGER.error(
							"Failed to start overlay HTTP server on {}:{} — StreamToEarn bridge is independent",
							bindHost,
							port,
							e);
				}
			}
		}
	}

	private static void startInternal(String bindHost, int port) throws Exception {
		HttpServer server = null;
		try {
			InetSocketAddress address = new InetSocketAddress(bindHost, port);
			server = HttpServer.create(address, 0);
			// Strict whitelist — no gift/chat/admin mutate routes.
			server.createContext("/arena/health", ArenaOverlayHttpIO::handleHealth);
			server.createContext("/arena/overlay-state", ArenaOverlayHttpIO::handleOverlayState);
			server.createContext("/api/arena/state", ArenaOverlayHttpIO::handleOverlayState);
			server.createContext("/overlay", ArenaOverlayHttpIO::handleOverlayRoot);
			server.createContext("/overlay/", ArenaOverlayHttpIO::handleOverlayAsset);

			ExecutorService httpExecutor = Executors.newCachedThreadPool(daemonFactory());
			server.setExecutor(httpExecutor);
			server.start();

			httpServer = server;
			executor = httpExecutor;
			running = true;
			runningBind = bindHost;
			runningPort = port;
			INSTANCE_COUNT.set(1);
			ArenaOfNations.LOGGER.info(
					"Overlay HTTP server started on http://{}:{} (whitelist only; S2E stays on separate port)",
					bindHost,
					port);
		} catch (Exception e) {
			if (server != null) {
				try {
					server.stop(0);
				} catch (Exception stopError) {
					ArenaOfNations.LOGGER.debug("Failed to stop partially started overlay HTTP server", stopError);
				}
			}
			running = false;
			httpServer = null;
			INSTANCE_COUNT.set(0);
			shutdownExecutorQuietly();
			throw e;
		}
	}

	private static void stop() {
		synchronized (LOCK) {
			if (httpServer != null) {
				try {
					httpServer.stop(0);
				} catch (Exception e) {
					ArenaOfNations.LOGGER.warn("Error while stopping overlay HTTP server", e);
				}
				httpServer = null;
			}
			shutdownExecutorQuietly();
			INSTANCE_COUNT.set(0);
			ACTIVE_THREADS.set(0);
			if (running) {
				running = false;
				ArenaOfNations.LOGGER.info("Overlay HTTP server stopped.");
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
			Thread thread = new Thread(() -> {
				ACTIVE_THREADS.incrementAndGet();
				try {
					runnable.run();
				} finally {
					ACTIVE_THREADS.decrementAndGet();
				}
			}, "arena-overlay-http");
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
		// Never echo tokens if somehow present in messages.
		if (message.toLowerCase().contains("eyj") || message.toLowerCase().contains("token")) {
			return e.getClass().getSimpleName();
		}
		return message.length() > 160 ? message.substring(0, 160) : message;
	}
}
