package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Thread-safe immutable JSON snapshot for external browser overlay.
 * Participants come from the current round registry (including WAITING holder).
 */
public final class ArenaOverlayStateService {
	public static final String PARTICIPANT_SOURCE = "CURRENT_ROUND_PARTICIPANTS";

	private static final ArenaOverlayStateService INSTANCE = new ArenaOverlayStateService();
	private static final long MIN_BATTLE_PUSH_INTERVAL_MS = 250L;

	private final AtomicReference<String> jsonSnapshot = new AtomicReference<>("{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[]}");
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicLong lastUpdateMs = new AtomicLong();
	private final AtomicLong requestCount = new AtomicLong();
	private final AtomicLong lastForcedPushMs = new AtomicLong();
	private final AtomicLong lastCountryCount = new AtomicLong();
	private final AtomicReference<String> lastDisplayedCodes = new AtomicReference<>("");
	private final AtomicReference<String> lastRoundParticipantCodes = new AtomicReference<>("");
	private final AtomicReference<String> lastEliminatedCodes = new AtomicReference<>("");
	private final AtomicReference<String> lastCardSizeMode = new AtomicReference<>("LARGE");
	private final AtomicReference<Integer> lastGridColumns = new AtomicReference<>(1);
	private final AtomicReference<Boolean> lastWaitingHolderIncluded = new AtomicReference<>(false);
	private final AtomicReference<String> lastRemovedCountry = new AtomicReference<>("-");
	private final AtomicReference<Boolean> lastImmediatePushAfterElimination = new AtomicReference<>(false);
	private final AtomicReference<String> lastTop5Countries = new AtomicReference<>("");
	private final AtomicLong lastTop5UpdateMs = new AtomicLong();

	private String lastBody = "";
	private volatile boolean nextPushIsElimination;

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

	/** Immediate overlay refresh after final elimination (also used via {@link ArenaRoundHudSync#pushNow}). */
	public static void pushNowAfterElimination(MinecraftServer server, Country removed) {
		INSTANCE.nextPushIsElimination = true;
		if (removed != null) {
			INSTANCE.lastRemovedCountry.set(removed.getCode());
		}
		INSTANCE.forceUpdate(server);
	}

	public String lastRoundParticipantCodes() {
		return lastRoundParticipantCodes.get();
	}

	public String lastEliminatedCodes() {
		return lastEliminatedCodes.get();
	}

	public String lastRemovedCountryCode() {
		return lastRemovedCountry.get();
	}

	public boolean lastImmediatePushAfterElimination() {
		return Boolean.TRUE.equals(lastImmediatePushAfterElimination.get());
	}

	public int lastDisplayedCountryCount() {
		return (int) lastCountryCount.get();
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

	public String lastDisplayedCountryCodes() {
		return lastDisplayedCodes.get();
	}

	public String lastCardSizeMode() {
		return lastCardSizeMode.get();
	}

	public int lastGridColumns() {
		return lastGridColumns.get();
	}

	public boolean lastWaitingHolderIncluded() {
		return Boolean.TRUE.equals(lastWaitingHolderIncluded.get());
	}

	public String participantSource() {
		return PARTICIPANT_SOURCE;
	}

	public String lastTop5Countries() {
		return lastTop5Countries.get();
	}

	public long lastTop5UpdateAgeMs() {
		long ts = lastTop5UpdateMs.get();
		return ts <= 0 ? -1L : Math.max(0L, System.currentTimeMillis() - ts);
	}

	public String top5Source() {
		return ArenaTopCountriesRanking.SOURCE;
	}

	/** Test hook: publish an immutable JSON snapshot without touching the Minecraft world. */
	public void publishSnapshotForTest(String jsonBody, int countryCount) {
		String body = jsonBody == null || jsonBody.isBlank()
				? "{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[]}"
				: jsonBody;
		long seq = sequence.incrementAndGet();
		if (body.startsWith("{")) {
			jsonSnapshot.set("{\"sequence\":" + seq + "," + body.substring(1));
		} else {
			jsonSnapshot.set(body);
		}
		lastBody = body;
		lastUpdateMs.set(System.currentTimeMillis());
		lastCountryCount.set(Math.max(0, countryCount));
		ArenaOverlayLayout.LayoutPlan plan = ArenaOverlayLayout.planFor(countryCount);
		lastCardSizeMode.set(plan.cardSizeMode().name());
		lastGridColumns.set(plan.columns());
		lastDisplayedCodes.set(extractCodes(body));
		lastWaitingHolderIncluded.set(body.contains("\"phase\":\"WAITING_FOR_OPPONENT\"") && countryCount >= 1);
		lastRoundParticipantCodes.set("");
		lastEliminatedCodes.set("");
		lastRemovedCountry.set("-");
		lastImmediatePushAfterElimination.set(false);
		lastTop5Countries.set(extractTop5Diag(body));
		lastTop5UpdateMs.set(System.currentTimeMillis());
	}

	/** Test hook: clear participants to empty IDLE snapshot (simulates reset). */
	public void resetSnapshotForTest() {
		sequence.set(0);
		jsonSnapshot.set("{\"sequence\":0,\"phase\":\"IDLE\",\"countries\":[],\"topCountries\":[]}");
		lastBody = "{\"phase\":\"IDLE\",\"countries\":[],\"topCountries\":[]}";
		lastUpdateMs.set(System.currentTimeMillis());
		lastCountryCount.set(0);
		lastDisplayedCodes.set("");
		lastRoundParticipantCodes.set("");
		lastEliminatedCodes.set("");
		lastCardSizeMode.set(ArenaOverlayLayout.CardSizeMode.LARGE.name());
		lastGridColumns.set(1);
		lastWaitingHolderIncluded.set(false);
		lastRemovedCountry.set("-");
		lastImmediatePushAfterElimination.set(false);
		nextPushIsElimination = false;
		lastTop5Countries.set("");
		lastTop5UpdateMs.set(0L);
	}

	/**
	 * Current-round overlay participants in join order.
	 * Uses {@link ArenaMatchManager#getCurrentRoundCountries()} (LinkedHashSet insertion order).
	 * Does not use scoreboard, next-round queue, or phase-only filters.
	 */
	public static List<Country> collectCurrentRoundParticipants(ArenaMatchManager match) {
		LinkedHashSet<Country> countries = new LinkedHashSet<>(match.getCurrentRoundCountries());
		for (Country country : match.getActiveCountries()) {
			countries.add(country);
		}
		return new ArrayList<>(countries);
	}

	/**
	 * Browser cards: current-round participants that are not finally eliminated.
	 * RESCUE countries stay (eliminated=false while rescuing).
	 */
	public static List<Country> collectDisplayedOverlayCountries(ArenaMatchManager match) {
		List<Country> displayed = new ArrayList<>();
		ArenaCoreRescueManager rescue = ArenaCoreRescueManager.get();
		for (Country country : collectCurrentRoundParticipants(match)) {
			if (!rescue.isEliminated(country)) {
				displayed.add(country);
			}
		}
		return displayed;
	}

	public static List<Country> collectEliminatedOverlayCountries(ArenaMatchManager match) {
		List<Country> eliminated = new ArrayList<>();
		ArenaCoreRescueManager rescue = ArenaCoreRescueManager.get();
		for (Country country : collectCurrentRoundParticipants(match)) {
			if (rescue.isEliminated(country)) {
				eliminated.add(country);
			}
		}
		return eliminated;
	}

	private static String codesOf(List<Country> countries) {
		if (countries == null || countries.isEmpty()) {
			return "";
		}
		return countries.stream().map(Country::getCode).collect(Collectors.joining(","));
	}

	private void tick(MinecraftServer server) {
		if (!ArenaConfig.get().isOverlayEnabled()) {
			return;
		}
		try {
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
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("Overlay snapshot tick failed", e);
		}
	}

	private void forceUpdate(MinecraftServer server) {
		if (!ArenaConfig.get().isOverlayEnabled()) {
			return;
		}
		lastForcedPushMs.set(System.currentTimeMillis());
		publish(server, true);
	}

	private void publish(MinecraftServer server, boolean force) {
		try {
			boolean eliminationPush = nextPushIsElimination;
			nextPushIsElimination = false;
			BodyBuild built = buildBody(server);
			String body = built.body();
			long nowMs = System.currentTimeMillis();
			if (!force && body.equals(lastBody) && (nowMs - lastUpdateMs.get()) < 200L) {
				return;
			}

			String previousDisplayed = lastDisplayedCodes.get();
			if (previousDisplayed != null && !previousDisplayed.isBlank()) {
				LinkedHashSet<String> prev = new LinkedHashSet<>(List.of(previousDisplayed.split(",")));
				LinkedHashSet<String> next = built.displayedCodes().isBlank()
						? new LinkedHashSet<>()
						: new LinkedHashSet<>(List.of(built.displayedCodes().split(",")));
				prev.removeAll(next);
				if (!prev.isEmpty()) {
					lastRemovedCountry.set(prev.iterator().next());
				}
			}

			long seq = sequence.incrementAndGet();
			jsonSnapshot.set("{\"sequence\":" + seq + "," + body.substring(1));
			lastBody = body;
			lastUpdateMs.set(nowMs);
			lastCountryCount.set(built.countryCount());
			lastDisplayedCodes.set(built.displayedCodes());
			lastRoundParticipantCodes.set(built.roundParticipantCodes());
			lastEliminatedCodes.set(built.eliminatedCodes());
			lastCardSizeMode.set(built.cardSizeMode());
			lastGridColumns.set(built.gridColumns());
			lastWaitingHolderIncluded.set(built.waitingHolderIncluded());
			lastImmediatePushAfterElimination.set(eliminationPush);
			lastTop5Countries.set(built.top5Codes());
			lastTop5UpdateMs.set(nowMs);
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("Overlay snapshot publish failed", e);
		}
	}

	private record BodyBuild(
			String body,
			int countryCount,
			String displayedCodes,
			String roundParticipantCodes,
			String eliminatedCodes,
			String cardSizeMode,
			int gridColumns,
			boolean waitingHolderIncluded,
			String top5Codes) {
	}

	private static BodyBuild buildBody(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaMatchState state = match.getState();
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, server.overworld());

		List<Country> roundParticipants = collectCurrentRoundParticipants(match);
		List<Country> eliminatedCountries = collectEliminatedOverlayCountries(match);
		// Cards: current-round && !eliminated. RESCUE stays (not eliminated yet).
		List<Country> displayed = collectDisplayedOverlayCountries(match);

		boolean waiting = state == ArenaMatchState.WAITING_FOR_OPPONENT;
		boolean waitingHolderIncluded = waiting && !displayed.isEmpty();
		ArenaOverlayLayout.LayoutPlan plan = ArenaOverlayLayout.planFor(displayed.size());
		String roundCodes = codesOf(roundParticipants);
		String eliminatedCodes = codesOf(eliminatedCountries);
		String displayedCodes = codesOf(displayed);

		JsonObject root = new JsonObject();
		root.addProperty("phase", state.name());
		root.addProperty("title", "АРЕНА · " + ArenaRoundHudSync.buildSnapshot(server).formatStatusText());
		root.addProperty("remainingSeconds", match.getRemainingSeconds());
		// Top panel: non-eliminated countries currently shown as cards.
		root.addProperty("activeCountryCount", displayed.size());
		root.addProperty("overlayParticipantSource", PARTICIPANT_SOURCE);
		root.addProperty("overlayCanvasWidth", ArenaOverlayLayout.CANVAS_WIDTH);
		root.addProperty("overlayCanvasHeight", ArenaOverlayLayout.CANVAS_HEIGHT);
		root.addProperty("overlayGridColumns", plan.columns());
		root.addProperty("overlayCardSizeMode", plan.cardSizeMode().name());
		root.addProperty("overlayDensityClass", plan.densityClass());
		root.addProperty("overlayWaitingHolderIncluded", waitingHolderIncluded);
		root.addProperty("overlayOverflowDetected", !ArenaOverlayLayout.fitsWithoutOverflow(displayed.size()));
		root.addProperty("overlayRoundParticipants", roundCodes);
		root.addProperty("overlayEliminatedCountries", eliminatedCodes);
		root.addProperty("overlayDisplayedCountries", displayedCodes);
		root.addProperty("overlayDisplayedCountryCount", displayed.size());
		root.addProperty("overlayEliminatedCardsVisible", 0);
		root.addProperty("overlaySnapshotCountries", displayedCodes);

		JsonArray arr = new JsonArray();
		int joinOrder = 0;
		for (Country country : displayed) {
			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			boolean eliminated = false;
			boolean rescuing = ArenaCoreRescueManager.get().isRescuing(country);
			boolean livingProtected = fightLevel != null && ArenaCoreManager.get().isCoreProtected(fightLevel, country);
			boolean protectedCore = !rescuing && (waiting || livingProtected);
			String status = rescuing ? "RESCUE" : protectedCore ? "PROTECTED" : "VULNERABLE";
			int rescueSeconds = rescuing ? ArenaCoreRescueManager.get().getRescueRemainingSeconds(server, country) : 0;
			int slot = match.getBaseSlot(country);
			float maxHp = Math.max(1.0F, core.getMaxHealth());
			int percent = Math.round((core.getCurrentHealth() / maxHp) * 100.0F);

			JsonObject item = new JsonObject();
			item.addProperty("id", country.getId());
			item.addProperty("code", country.getCode());
			item.addProperty("name", country.getDisplayName());
			item.addProperty("joinOrder", joinOrder);
			item.addProperty("baseSlot", slot);
			item.addProperty("activeFighters", match.getLiveFighterCount(server, country));
			item.addProperty("reserve", match.getReserveSize(country));
			item.addProperty("coreHp", Math.round(core.getCurrentHealth()));
			item.addProperty("coreMaxHp", Math.round(maxHp));
			item.addProperty("corePercent", Math.max(0, Math.min(100, percent)));
			item.addProperty("coreProtected", protectedCore);
			item.addProperty("coreVulnerable", !rescuing && !protectedCore);
			item.addProperty("status", status);
			item.addProperty("rescueSeconds", rescueSeconds);
			item.addProperty("rescueRemaining", rescueSeconds);
			item.addProperty("eliminated", eliminated);
			arr.add(item);
			joinOrder++;
		}
		root.add("countries", arr);

		JsonArray topArr = new JsonArray();
		List<ArenaTopCountriesRanking.Entry> top = ArenaScoreManager.topByRoundWins(
				server, ArenaTopCountriesRanking.DEFAULT_LIMIT);
		StringBuilder topDiag = new StringBuilder();
		for (ArenaTopCountriesRanking.Entry entry : top) {
			Country country = entry.country();
			JsonObject item = new JsonObject();
			item.addProperty("rank", entry.rank());
			item.addProperty("countryId", country.getId());
			item.addProperty("displayName", country.getDisplayName());
			item.addProperty("code", country.getCode());
			item.addProperty("flagUrl", "/overlay/tiktok/flags/" + country.getId() + ".png");
			item.addProperty("roundWins", entry.roundWins());
			item.addProperty("scorePoints", entry.scorePoints());
			item.addProperty("winsLabel", ArenaRoundWinsGrammar.formatWins(entry.roundWins()));
			topArr.add(item);
			if (topDiag.length() > 0) {
				topDiag.append(',');
			}
			topDiag.append(country.getCode()).append(':').append(entry.roundWins());
		}
		root.add("topCountries", topArr);
		root.addProperty("top5Source", ArenaTopCountriesRanking.SOURCE);
		root.addProperty("top5Count", top.size());

		JsonObject record = new JsonObject();
		Country recordCountry = ArenaScoreManager.getFighterRoundRecordCountry(server);
		int recordCount = ArenaScoreManager.getFighterRoundRecordCount(server);
		if (recordCountry != null && recordCount > 0) {
			record.addProperty("countryId", recordCountry.getId());
			record.addProperty("displayName", recordCountry.getDisplayName());
			record.addProperty("flagKey", recordCountry.getId());
			record.addProperty("fighterCount", recordCount);
		} else {
			record.addProperty("countryId", "");
			record.addProperty("displayName", "");
			record.addProperty("flagKey", "");
			record.addProperty("fighterCount", 0);
		}
		root.add("fighterRoundRecord", record);

		JsonObject sent = new JsonObject();
		for (var entry : match.getFightersSentThisRoundSnapshot().entrySet()) {
			sent.addProperty(entry.getKey().getCode(), entry.getValue());
		}
		root.add("currentRoundFightersSent", sent);
		root.addProperty("statsResetAllowed", ArenaStatsResetService.isResetAllowed());

		JsonObject runtimeSettings = new JsonObject();
		runtimeSettings.addProperty(
				"reserveReleaseBatch",
				ArenaReserveRuntimeSettings.get().getReserveReleaseBatch());
		root.add("runtimeSettings", runtimeSettings);

		return new BodyBuild(
				root.toString(),
				displayed.size(),
				displayedCodes,
				roundCodes,
				eliminatedCodes,
				plan.cardSizeMode().name(),
				plan.columns(),
				waitingHolderIncluded,
				topDiag.toString());
	}

	private static String extractTop5Diag(String body) {
		if (body == null || !body.contains("\"topCountries\"")) {
			return "";
		}
		// Best-effort for test snapshots that already embed topCountries.
		return body.contains("\"roundWins\"") ? "TEST" : "";
	}

	private static String extractCodes(String body) {
		if (body == null || !body.contains("\"code\"")) {
			return "";
		}
		List<String> codes = new ArrayList<>();
		int idx = 0;
		while (true) {
			int codeKey = body.indexOf("\"code\"", idx);
			if (codeKey < 0) {
				break;
			}
			int colon = body.indexOf(':', codeKey);
			int q1 = body.indexOf('"', colon + 1);
			int q2 = q1 >= 0 ? body.indexOf('"', q1 + 1) : -1;
			if (q1 >= 0 && q2 > q1) {
				codes.add(body.substring(q1 + 1, q2).toUpperCase(Locale.ROOT));
			}
			idx = Math.max(codeKey + 6, q2 + 1);
		}
		return codes.stream().distinct().collect(Collectors.joining(","));
	}
}
