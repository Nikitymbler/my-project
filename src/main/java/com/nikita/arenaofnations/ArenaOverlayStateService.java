package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Thread-safe immutable JSON snapshot for external browser overlay.
 */
public final class ArenaOverlayStateService {
	private static final ArenaOverlayStateService INSTANCE = new ArenaOverlayStateService();
	private static final long MIN_BATTLE_PUSH_INTERVAL_MS = 250L;

	private final AtomicReference<String> jsonSnapshot = new AtomicReference<>("{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[]}");
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicLong lastUpdateMs = new AtomicLong();
	private final AtomicLong requestCount = new AtomicLong();
	private final AtomicLong lastForcedPushMs = new AtomicLong();
	private final AtomicLong lastCountryCount = new AtomicLong();

	private String lastBody = "";

	private ArenaOverlayStateService() {
	}

	public static ArenaOverlayStateService get() {
		return INSTANCE;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.tick(server));
	}

	public static void pushNow(MinecraftServer server) {
		INSTANCE.forceUpdate(server);
	}

	public String snapshotJson() {
		return jsonSnapshot.get();
	}

	public long snapshotSequence() {
		return sequence.get();
	}

	public long lastSnapshotAgeMs() {
		long ts = lastUpdateMs.get();
		return ts <= 0 ? -1L : Math.max(0L, System.currentTimeMillis() - ts);
	}

	public long markAndGetRequestCount() {
		return requestCount.incrementAndGet();
	}

	public long requestCount() {
		return requestCount.get();
	}

	public int snapshotCountryCount() {
		return (int) lastCountryCount.get();
	}

	private void tick(MinecraftServer server) {
		if (!ArenaConfig.get().isOverlayEnabled()) {
			return;
		}
		ArenaMatchState state = ArenaMatchManager.get().getState();
		long nowMs = System.currentTimeMillis();
		boolean battle = state == ArenaMatchState.BATTLE;
		if (battle && (nowMs - lastForcedPushMs.get()) < MIN_BATTLE_PUSH_INTERVAL_MS) {
			BodyBuild built = buildBody(server);
			if (built.body().equals(lastBody) && (nowMs - lastUpdateMs.get()) < 200L) {
				return;
			}
		}
		publish(server, false);
	}

	private void forceUpdate(MinecraftServer server) {
		if (!ArenaConfig.get().isOverlayEnabled()) {
			return;
		}
		lastForcedPushMs.set(System.currentTimeMillis());
		publish(server, true);
	}

	private void publish(MinecraftServer server, boolean force) {
		BodyBuild built = buildBody(server);
		String body = built.body();
		long nowMs = System.currentTimeMillis();
		if (!force && body.equals(lastBody) && (nowMs - lastUpdateMs.get()) < 200L) {
			return;
		}
		long seq = sequence.incrementAndGet();
		jsonSnapshot.set("{\"sequence\":" + seq + "," + body.substring(1));
		lastBody = body;
		lastUpdateMs.set(nowMs);
		lastCountryCount.set(built.countryCount());
	}

	private record BodyBuild(String body, int countryCount) {
	}

	private static BodyBuild buildBody(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaMatchState state = match.getState();
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, server.overworld());

		LinkedHashSet<Country> countries = new LinkedHashSet<>(match.getCurrentRoundCountries());
		countries.addAll(match.getActiveCountries());
		countries.addAll(ArenaCoreRescueManager.get().getEliminatedCountries());
		List<Country> ordered = new ArrayList<>(countries);
		ordered.sort(Comparator.comparingInt(c -> {
			int slot = match.getBaseSlot(c);
			return slot < 0 ? 999 : slot;
		}));

		JsonObject root = new JsonObject();
		root.addProperty("phase", state.name());
		root.addProperty("title", "АРЕНА · " + ArenaRoundHudSync.buildSnapshot(server).formatStatusText());
		root.addProperty("remainingSeconds", match.getRemainingSeconds());
		root.addProperty("activeCountryCount", match.getActiveCountries().size());

		JsonArray arr = new JsonArray();
		for (Country country : ordered) {
			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			boolean eliminated = ArenaCoreRescueManager.get().isEliminated(country);
			boolean rescuing = !eliminated && ArenaCoreRescueManager.get().isRescuing(country);
			boolean protectedCore = fightLevel != null && ArenaCoreManager.get().isCoreProtected(fightLevel, country);
			String status = eliminated ? "ELIMINATED" : rescuing ? "RESCUE" : protectedCore ? "PROTECTED" : "VULNERABLE";
			int rescueSeconds = rescuing ? ArenaCoreRescueManager.get().getRescueRemainingSeconds(server, country) : 0;
			int slot = match.getBaseSlot(country);
			float maxHp = Math.max(1.0F, core.getMaxHealth());
			int percent = Math.round((core.getCurrentHealth() / maxHp) * 100.0F);

			JsonObject item = new JsonObject();
			item.addProperty("id", country.getId());
			item.addProperty("code", country.getCode());
			item.addProperty("name", country.getDisplayName());
			item.addProperty("baseSlot", slot);
			item.addProperty("activeFighters", match.getLiveFighterCount(server, country));
			item.addProperty("reserve", match.getReserveSize(country));
			item.addProperty("coreHp", Math.round(core.getCurrentHealth()));
			item.addProperty("coreMaxHp", Math.round(maxHp));
			item.addProperty("corePercent", Math.max(0, Math.min(100, percent)));
			item.addProperty("status", status);
			item.addProperty("rescueSeconds", rescueSeconds);
			item.addProperty("eliminated", eliminated);
			arr.add(item);
		}
		root.add("countries", arr);
		return new BodyBuild(root.toString(), ordered.size());
	}
}
