package com.nikita.arenaofnations;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.server.MinecraftServer;

/**
 * Live-editable {@code reserveReleaseBatch} (persisted as {@code reserve_wave_size}).
 * Scheduler always reads {@link #getReserveReleaseBatch()} — never a round-start cache.
 */
public final class ArenaReserveRuntimeSettings {
	public static final int MIN_BATCH = 1;
	public static final int MAX_BATCH = 100;
	public static final int DEFAULT_BATCH = 10;

	public enum ChangedBy {
		BROWSER,
		COMMAND,
		CONFIG
	}

	private static final ArenaReserveRuntimeSettings INSTANCE = new ArenaReserveRuntimeSettings();

	private final AtomicInteger reserveReleaseBatch = new AtomicInteger(DEFAULT_BATCH);

	private volatile ChangedBy lastChangedBy = ChangedBy.CONFIG;
	private volatile String lastChangedAt = "-";
	private volatile int lastRequested = -1;
	private volatile int lastConfiguredBatch = -1;
	private volatile int lastReserveBefore = -1;
	private volatile int lastAvailableSlots = -1;
	private volatile int lastActual = -1;
	private volatile String lastCountry = "-";
	private volatile String lastError = "";

	private ArenaReserveRuntimeSettings() {
	}

	public static ArenaReserveRuntimeSettings get() {
		return INSTANCE;
	}

	public int getReserveReleaseBatch() {
		return reserveReleaseBatch.get();
	}

	/**
	 * Active living-fighters cap used by the release formula.
	 * Current project runs unlimited live-field mode — do not invent a new gameplay cap.
	 */
	public int getActiveFightersLimit() {
		return Integer.MAX_VALUE;
	}

	public int getReserveReleaseIntervalTicks() {
		return ArenaConfig.get().getReserveWaveIntervalTicks();
	}

	public ChangedBy lastChangedBy() {
		return lastChangedBy;
	}

	public String lastChangedAt() {
		return lastChangedAt == null ? "-" : lastChangedAt;
	}

	public int lastRequested() {
		return lastRequested;
	}

	public int lastConfiguredBatch() {
		return lastConfiguredBatch;
	}

	public int lastReserveBefore() {
		return lastReserveBefore;
	}

	public int lastAvailableSlots() {
		return lastAvailableSlots;
	}

	public int lastActual() {
		return lastActual;
	}

	public String lastCountry() {
		return lastCountry == null ? "-" : lastCountry;
	}

	public String lastError() {
		return lastError == null ? "" : lastError;
	}

	/** Sync after config load/reload. Does not rewrite disk. */
	public void syncFromConfig(int loadedBatch) {
		int clamped = clampOrDefault(loadedBatch);
		reserveReleaseBatch.set(clamped);
		lastChangedBy = ChangedBy.CONFIG;
		lastChangedAt = Instant.now().toString();
		lastRequested = clamped;
		lastError = "";
	}

	public ApplyResult apply(int requested, ChangedBy source) {
		lastRequested = requested;
		if (requested < MIN_BATCH || requested > MAX_BATCH) {
			return ApplyResult.fail(400, "Допустимое значение: от " + MIN_BATCH + " до " + MAX_BATCH + ".");
		}
		try {
			ArenaConfig.get().setReserveWaveSizeAndSave(requested);
			reserveReleaseBatch.set(requested);
			lastChangedBy = source == null ? ChangedBy.COMMAND : source;
			lastChangedAt = Instant.now().toString();
			lastError = "";
			return ApplyResult.ok(requested, "Размер выпуска резерва изменён на " + requested);
		} catch (IOException e) {
			lastError = e.getClass().getSimpleName();
			ArenaOfNations.LOGGER.error("Failed to persist reserveReleaseBatch={}", requested, e);
			return ApplyResult.fail(500, "Ошибка записи конфигурации: " + e.getClass().getSimpleName());
		} catch (IllegalArgumentException e) {
			lastError = e.getMessage() == null ? "invalid" : e.getMessage();
			return ApplyResult.fail(400, lastError);
		}
	}

	public ApplyResult applyOnServerThread(int requested, ChangedBy source) {
		MinecraftServer server = ArenaOverlayHttpServer.getActiveServer();
		if (server == null) {
			return apply(requested, source);
		}
		CompletableFuture<ApplyResult> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				future.complete(apply(requested, source));
			} catch (Exception e) {
				future.complete(ApplyResult.fail(500, e.getClass().getSimpleName()));
			}
		});
		try {
			return future.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			lastError = e.getClass().getSimpleName();
			return ApplyResult.fail(500, "Сервер не применил настройку: " + e.getClass().getSimpleName());
		}
	}

	public void recordWaveAttempt(
			Country country,
			int configuredBatch,
			int reserveBefore,
			int availableSlots,
			int actualReleased,
			String error) {
		lastConfiguredBatch = configuredBatch;
		lastReserveBefore = reserveBefore;
		lastAvailableSlots = availableSlots;
		lastActual = actualReleased;
		lastCountry = country == null ? "-" : country.getCode();
		if (error != null && !error.isBlank()) {
			lastError = error;
		}
	}

	public String buildDiagnosticLines() {
		StringBuilder out = new StringBuilder();
		out.append("reserveReleaseBatch=").append(getReserveReleaseBatch()).append('\n');
		out.append("reserveReleaseBatchMin=").append(MIN_BATCH).append('\n');
		out.append("reserveReleaseBatchMax=").append(MAX_BATCH).append('\n');
		out.append("reserveReleaseBatchLiveEditable=true\n");
		out.append("reserveReleaseBatchPersistent=true\n");
		out.append("reserveReleaseBatchLastChangedBy=").append(lastChangedBy.name()).append('\n');
		out.append("reserveReleaseBatchLastChangedAt=").append(lastChangedAt()).append('\n');
		out.append("reserveReleaseBatchLastRequested=").append(lastRequested).append('\n');
		out.append("reserveReleaseLastConfiguredBatch=").append(lastConfiguredBatch).append('\n');
		out.append("reserveReleaseLastReserveBefore=").append(lastReserveBefore).append('\n');
		out.append("reserveReleaseLastAvailableSlots=").append(lastAvailableSlots).append('\n');
		out.append("reserveReleaseLastActual=").append(lastActual).append('\n');
		out.append("reserveReleaseLastCountry=").append(lastCountry()).append('\n');
		out.append("reserveReleaseLastError=").append(blank(lastError()));
		return out.toString();
	}

	public static int clampOrDefault(int value) {
		if (value < MIN_BATCH || value > MAX_BATCH) {
			return DEFAULT_BATCH;
		}
		return value;
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	public record ApplyResult(boolean success, int reserveReleaseBatch, String message, int httpStatus) {
		static ApplyResult ok(int batch, String message) {
			return new ApplyResult(true, batch, message, 200);
		}

		static ApplyResult fail(int status, String message) {
			return new ApplyResult(false, INSTANCE.getReserveReleaseBatch(), message, status);
		}
	}
}
