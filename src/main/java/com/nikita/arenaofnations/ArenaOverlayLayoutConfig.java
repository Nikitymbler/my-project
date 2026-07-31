package com.nikita.arenaofnations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side overlay module layout (ratios + visibility + scale) under Fabric config dir.
 */
public final class ArenaOverlayLayoutConfig {
	public static final int VERSION = 3;
	public static final String FILE_NAME = "arena_of_nations_overlay_layout.json";

	public static final double DEFAULT_BATTLE_X = 0.04;
	public static final double DEFAULT_BATTLE_Y = 0.02;
	public static final double DEFAULT_TOP5_X = 0.68;
	public static final double DEFAULT_TOP5_Y = 0.22;
	public static final double DEFAULT_RECORD_X = 0.84;
	public static final double DEFAULT_RECORD_Y = 0.08;
	public static final double DEFAULT_SCALE = 1.0;
	public static final double MIN_SCALE = 0.5;
	public static final double MAX_SCALE = 2.0;

	private static final Object LOCK = new Object();
	private static final AtomicReference<LayoutState> CACHED = new AtomicReference<>(defaults());
	private static final AtomicBoolean LOADED = new AtomicBoolean(false);
	private static final AtomicBoolean LAST_SAVE_OK = new AtomicBoolean(true);
	private static final AtomicReference<String> LAST_SAVE_ERROR = new AtomicReference<>("");
	private static final AtomicBoolean LEGACY_MIGRATED = new AtomicBoolean(false);

	private ArenaOverlayLayoutConfig() {
	}

	public record ModuleLayout(double xRatio, double yRatio, boolean visible, double scale) {
		public ModuleLayout(double xRatio, double yRatio, boolean visible) {
			this(xRatio, yRatio, visible, DEFAULT_SCALE);
		}
	}

	public record LayoutState(
			int version,
			ModuleLayout battle,
			ModuleLayout top5,
			ModuleLayout record,
			boolean legacyLocalStorageMigrated) {
	}

	public static Path configPath() {
		String override = System.getProperty("arena.overlay.layout.config");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static LayoutState defaults() {
		return new LayoutState(
				VERSION,
				new ModuleLayout(DEFAULT_BATTLE_X, DEFAULT_BATTLE_Y, true, DEFAULT_SCALE),
				new ModuleLayout(DEFAULT_TOP5_X, DEFAULT_TOP5_Y, true, DEFAULT_SCALE),
				new ModuleLayout(DEFAULT_RECORD_X, DEFAULT_RECORD_Y, true, DEFAULT_SCALE),
				false);
	}

	public static boolean isLoaded() {
		return LOADED.get();
	}

	public static boolean lastSaveSuccess() {
		return LAST_SAVE_OK.get();
	}

	public static String lastSaveError() {
		String err = LAST_SAVE_ERROR.get();
		return err == null ? "" : err;
	}

	public static boolean legacyLocalStorageMigrated() {
		return LEGACY_MIGRATED.get() || CACHED.get().legacyLocalStorageMigrated();
	}

	public static LayoutState current() {
		ensureLoaded();
		return CACHED.get();
	}

	public static String currentJson() {
		LayoutState state = current();
		boolean exists = Files.isRegularFile(configPath());
		String base = toJson(state);
		return base.substring(0, base.length() - 1)
				+ ",\"configFileExists\":"
				+ exists
				+ "}";
	}

	public static void ensureLoaded() {
		synchronized (LOCK) {
			if (LOADED.get()) {
				return;
			}
			Path path = configPath();
			try {
				if (Files.isRegularFile(path)) {
					String raw = Files.readString(path, StandardCharsets.UTF_8);
					LayoutState parsed = parse(raw);
					CACHED.set(parsed);
					LEGACY_MIGRATED.set(parsed.legacyLocalStorageMigrated());
					LAST_SAVE_OK.set(true);
					LAST_SAVE_ERROR.set("");
				} else {
					CACHED.set(defaults());
				}
			} catch (Exception e) {
				ArenaOfNations.LOGGER.warn("Failed to load overlay layout config, using defaults: {}", e.toString());
				CACHED.set(defaults());
				LAST_SAVE_OK.set(false);
				LAST_SAVE_ERROR.set(e.getClass().getSimpleName());
			}
			LOADED.set(true);
		}
	}

	public static LayoutState parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("empty_json");
		}
		JsonObject root;
		try {
			root = JsonParser.parseString(raw).getAsJsonObject();
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new IllegalArgumentException("invalid_json");
		}
		LayoutState base = defaults();
		int version = root.has("version") && root.get("version").isJsonPrimitive()
				? root.get("version").getAsInt()
				: VERSION;
		ModuleLayout battle = readModule(root, "battle", base.battle());
		ModuleLayout top5 = readModule(root, "top5", base.top5());
		ModuleLayout record = readModule(root, "record", base.record());
		boolean migrated = root.has("legacyLocalStorageMigrated")
				&& root.get("legacyLocalStorageMigrated").isJsonPrimitive()
				&& root.get("legacyLocalStorageMigrated").getAsBoolean();
		if (root.has("migratedFromLocalStorage")
				&& root.get("migratedFromLocalStorage").isJsonPrimitive()
				&& root.get("migratedFromLocalStorage").getAsBoolean()) {
			migrated = true;
		}
		return new LayoutState(version <= 0 ? VERSION : Math.max(version, VERSION), battle, top5, record, migrated);
	}

	private static ModuleLayout readModule(JsonObject root, String key, ModuleLayout fallback) {
		if (!root.has(key) || !root.get(key).isJsonObject()) {
			return fallback;
		}
		JsonObject obj = root.getAsJsonObject(key);
		double x = fallback.xRatio();
		double y = fallback.yRatio();
		boolean visible = fallback.visible();
		double scale = fallback.scale();
		if (obj.has("xRatio") && obj.get("xRatio").isJsonPrimitive()) {
			x = obj.get("xRatio").getAsDouble();
		}
		if (obj.has("yRatio") && obj.get("yRatio").isJsonPrimitive()) {
			y = obj.get("yRatio").getAsDouble();
		}
		if (obj.has("visible") && obj.get("visible").isJsonPrimitive()) {
			visible = obj.get("visible").getAsBoolean();
		}
		if (obj.has("scale") && obj.get("scale").isJsonPrimitive()) {
			scale = obj.get("scale").getAsDouble();
		}
		return new ModuleLayout(clamp01(x), clamp01(y), visible, clampScale(scale));
	}

	public static double clamp01(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}

	public static double clampScale(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return DEFAULT_SCALE;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}

	public static String toJson(LayoutState state) {
		LayoutState s = state == null ? defaults() : state;
		return "{"
				+ "\"version\":" + VERSION + ","
				+ "\"battle\":" + moduleJson(s.battle()) + ","
				+ "\"top5\":" + moduleJson(s.top5()) + ","
				+ "\"record\":" + moduleJson(s.record()) + ","
				+ "\"legacyLocalStorageMigrated\":" + s.legacyLocalStorageMigrated()
				+ "}";
	}

	private static String moduleJson(ModuleLayout m) {
		return "{"
				+ "\"xRatio\":" + ratio(m.xRatio()) + ","
				+ "\"yRatio\":" + ratio(m.yRatio()) + ","
				+ "\"visible\":" + m.visible() + ","
				+ "\"scale\":" + ratio(clampScale(m.scale()))
				+ "}";
	}

	private static String ratio(double value) {
		return String.format(java.util.Locale.ROOT, "%.6f", value);
	}

	public static LayoutState save(LayoutState state) throws IOException {
		synchronized (LOCK) {
			LayoutState normalized = new LayoutState(
					VERSION,
					normalizeModule(state.battle()),
					normalizeModule(state.top5()),
					normalizeModule(state.record()),
					state.legacyLocalStorageMigrated() || LEGACY_MIGRATED.get());
			writeAtomic(normalized);
			CACHED.set(normalized);
			LOADED.set(true);
			LEGACY_MIGRATED.set(normalized.legacyLocalStorageMigrated());
			LAST_SAVE_OK.set(true);
			LAST_SAVE_ERROR.set("");
			return normalized;
		}
	}

	private static ModuleLayout normalizeModule(ModuleLayout module) {
		ModuleLayout fallback = new ModuleLayout(0, 0, true, DEFAULT_SCALE);
		ModuleLayout src = module == null ? fallback : module;
		return new ModuleLayout(
				clamp01(src.xRatio()),
				clamp01(src.yRatio()),
				src.visible(),
				clampScale(src.scale()));
	}

	public static LayoutState resetToDefaults() throws IOException {
		LayoutState next = new LayoutState(
				VERSION,
				new ModuleLayout(DEFAULT_BATTLE_X, DEFAULT_BATTLE_Y, true, DEFAULT_SCALE),
				new ModuleLayout(DEFAULT_TOP5_X, DEFAULT_TOP5_Y, true, DEFAULT_SCALE),
				new ModuleLayout(DEFAULT_RECORD_X, DEFAULT_RECORD_Y, true, DEFAULT_SCALE),
				legacyLocalStorageMigrated());
		return save(next);
	}

	private static void writeAtomic(LayoutState state) throws IOException {
		Path path = configPath();
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		byte[] bytes = toJson(state).getBytes(StandardCharsets.UTF_8);
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best-effort cleanup
			}
		}
	}

	public static void resetForTest() {
		synchronized (LOCK) {
			CACHED.set(defaults());
			LOADED.set(false);
			LAST_SAVE_OK.set(true);
			LAST_SAVE_ERROR.set("");
			LEGACY_MIGRATED.set(false);
		}
	}

	public static void markSaveFailed(String reason) {
		LAST_SAVE_OK.set(false);
		LAST_SAVE_ERROR.set(reason == null ? "error" : reason);
	}
}
