package com.nikita.arenaofnations;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Platform-independent viewer event queue. External adapters enqueue chat/gifts;
 * match logic runs only on the server tick via {@link ArenaMatchManager#handleGift}.
 */
public final class ArenaViewerEventManager {
	private static final ArenaViewerEventManager INSTANCE = new ArenaViewerEventManager();

	private static final int MAX_DEDUP_IDS = 20_000;
	private static final int MAX_VIEWER_COUNTRY_SELECTIONS = 5_000;
	private static final int MAX_EVENTS_PER_TICK = 256;
	private static final int DEDUP_CLEANUP_INTERVAL_TICKS = 200;

	private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
	private final AtomicInteger queueSize = new AtomicInteger(0);
	private final Object queueMutex = new Object();
	/** Access-ordered; eldest entries evicted when over {@link #MAX_VIEWER_COUNTRY_SELECTIONS}. */
	private final Map<String, Country> countryByViewer = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Country> eldest) {
			return size() > MAX_VIEWER_COUNTRY_SELECTIONS;
		}
	};
	private final Object countryByViewerMutex = new Object();

	private final Map<String, Long> processedGiftIds = new LinkedHashMap<>();

	private final AtomicLong acceptedChatEvents = new AtomicLong();
	private final AtomicLong acceptedGifts = new AtomicLong();
	private final AtomicLong rejectedGifts = new AtomicLong();
	private final AtomicLong duplicateGifts = new AtomicLong();
	private final AtomicLong giftsWithoutEventId = new AtomicLong();
	private final AtomicLong queueOverflows = new AtomicLong();

	private volatile String lastViewer = "";
	private volatile String lastGiftSummary = "";
	private volatile String lastCountryCode = "";
	private volatile int lastFighterCount;
	private volatile String lastError = "";
	private volatile long lastEventGameTime = -1L;
	private volatile String lastEventKind = "";

	private int ticksSinceDedupCleanup = 0;

	private ArenaViewerEventManager() {
	}

	public static ArenaViewerEventManager get() {
		return INSTANCE;
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_viewer_chat")
					.requires(source -> source.hasPermission(2))
					.then(Commands.argument("viewerId", StringArgumentType.word())
							.then(Commands.argument("message", StringArgumentType.greedyString())
									.executes(context -> enqueueChatCommand(context.getSource(),
											StringArgumentType.getString(context, "viewerId"),
											StringArgumentType.getString(context, "message"))))));

			dispatcher.register(Commands.literal("arena_viewer_gift")
					.requires(source -> source.hasPermission(2))
					.then(Commands.argument("viewerId", StringArgumentType.word())
							.then(Commands.argument("coins", IntegerArgumentType.integer())
									.executes(context -> enqueueGiftCommand(
											context.getSource(),
											StringArgumentType.getString(context, "viewerId"),
											IntegerArgumentType.getInteger(context, "coins"),
											null))
									.then(Commands.argument("eventId", StringArgumentType.word())
											.executes(context -> enqueueGiftCommand(
													context.getSource(),
													StringArgumentType.getString(context, "viewerId"),
													IntegerArgumentType.getInteger(context, "coins"),
													StringArgumentType.getString(context, "eventId")))))));

			dispatcher.register(Commands.literal("arena_viewer_status")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						context.getSource().sendSuccess(
								() -> Component.literal(INSTANCE.buildStatusText()), false);
						return 1;
					}));

			dispatcher.register(Commands.literal("arena_viewer_reset")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						INSTANCE.clearTransientState();
						context.getSource().sendSuccess(
								() -> Component.literal(
										"Состояние зрительских событий очищено (очередь, выбор стран, дедуп, счётчики)."),
								false);
						return 1;
					}));
		});
	}

	/**
	 * Enqueue a chat message. Safe to call from any thread.
	 *
	 * @return {@code true} if accepted into the queue
	 */
	public boolean enqueueChat(String viewerId, String viewerName, String message, String eventId) {
		if (viewerId == null || viewerId.isBlank()) {
			return false;
		}
		String id = viewerId.trim();
		String name = (viewerName == null || viewerName.isBlank()) ? id : viewerName.trim();
		String text = message == null ? "" : message;
		return offer(new ViewerChatEvent(id, name, text, normalizeEventId(eventId)));
	}

	/**
	 * Enqueue a gift. Safe to call from any thread.
	 *
	 * @return {@code true} if accepted into the queue
	 */
	public boolean enqueueGift(String viewerId, String viewerName, int coins, String eventId) {
		if (viewerId == null || viewerId.isBlank()) {
			return false;
		}
		String id = viewerId.trim();
		String name = (viewerName == null || viewerName.isBlank()) ? id : viewerName.trim();
		return offer(new ViewerGiftEvent(id, name, coins, normalizeEventId(eventId)));
	}

	public Country getSelectedCountry(String viewerId) {
		if (viewerId == null || viewerId.isBlank()) {
			return null;
		}
		return countryByViewer.get(viewerId.trim());
	}

	public int getQueueSize() {
		return queueSize.get();
	}

	public long getAcceptedChatEvents() {
		return acceptedChatEvents.get();
	}

	public long getAcceptedGifts() {
		return acceptedGifts.get();
	}

	public long getRejectedGifts() {
		return rejectedGifts.get();
	}

	public long getDuplicateGifts() {
		return duplicateGifts.get();
	}

	public long getGiftsWithoutEventId() {
		return giftsWithoutEventId.get();
	}

	public long getQueueOverflows() {
		return queueOverflows.get();
	}

	public String getLastViewer() {
		return lastViewer;
	}

	public String getLastGiftSummary() {
		return lastGiftSummary;
	}

	public String getLastCountryCode() {
		return lastCountryCode;
	}

	public int getLastFighterCount() {
		return lastFighterCount;
	}

	public String getLastError() {
		return lastError;
	}

	public String getLastEventKind() {
		return lastEventKind;
	}

	public long getLastEventGameTime() {
		return lastEventGameTime;
	}

	public int getViewerSelectionCount() {
		synchronized (countryByViewerMutex) {
			return countryByViewer.size();
		}
	}

	public EnumMap<Country, Integer> getCountrySelectionCounts() {
		EnumMap<Country, Integer> counts = new EnumMap<>(Country.class);
		for (Country country : Country.values()) {
			counts.put(country, 0);
		}
		synchronized (countryByViewerMutex) {
			for (Country country : countryByViewer.values()) {
				counts.merge(country, 1, Integer::sum);
			}
		}
		return counts;
	}

	public void clearTransientState() {
		synchronized (queueMutex) {
			queue.clear();
			queueSize.set(0);
		}
		synchronized (countryByViewerMutex) {
			countryByViewer.clear();
		}
		synchronized (processedGiftIds) {
			processedGiftIds.clear();
		}
		acceptedChatEvents.set(0);
		acceptedGifts.set(0);
		rejectedGifts.set(0);
		duplicateGifts.set(0);
		giftsWithoutEventId.set(0);
		queueOverflows.set(0);
		lastViewer = "";
		lastGiftSummary = "";
		lastCountryCode = "";
		lastFighterCount = 0;
		lastError = "";
		lastEventGameTime = -1L;
		lastEventKind = "";
		ticksSinceDedupCleanup = 0;
	}

	public String buildStatusText() {
		ArenaConfig config = ArenaConfig.get();
		EnumMap<Country, Integer> distribution = getCountrySelectionCounts();
		StringBuilder builder = new StringBuilder();
		builder.append("Viewer events:\n");
		builder.append("viewer_events_enabled=").append(config.isViewerEventsEnabled()).append('\n');
		builder.append("очередь=").append(queueSize.get())
				.append('/').append(config.getViewerEventQueueLimit()).append('\n');
		builder.append("зрителей с выбранной страной=").append(countryByViewer.size()).append('\n');
		builder.append("принятые chat=").append(acceptedChatEvents.get()).append('\n');
		builder.append("принятые gifts=").append(acceptedGifts.get()).append('\n');
		builder.append("отклонённые gifts=").append(rejectedGifts.get()).append('\n');
		builder.append("duplicates=").append(duplicateGifts.get()).append('\n');
		builder.append("gifts без eventId=").append(giftsWithoutEventId.get()).append('\n');
		builder.append("переполнения очереди=").append(queueOverflows.get()).append('\n');
		builder.append("выбор стран:");
		for (Country country : Country.values()) {
			builder.append('\n')
					.append("- ")
					.append(country.getDisplayName())
					.append(": ")
					.append(distribution.getOrDefault(country, 0));
		}
		return builder.toString();
	}

	private boolean offer(Object event) {
		int limit = ArenaConfig.get().getViewerEventQueueLimit();
		synchronized (queueMutex) {
			if (queueSize.get() >= limit) {
				queueOverflows.incrementAndGet();
				return false;
			}
			queue.offer(event);
			queueSize.incrementAndGet();
			return true;
		}
	}

	/**
	 * Drain up to 256 queued viewer events on the server thread.
	 * Called from {@link ArenaMatchManager} before match/rescue ticks.
	 */
	public void processQueuedEvents(MinecraftServer server) {
		ticksSinceDedupCleanup++;
		if (ticksSinceDedupCleanup >= DEDUP_CLEANUP_INTERVAL_TICKS) {
			ticksSinceDedupCleanup = 0;
			cleanupDedupCache(server.overworld().getGameTime());
		}

		int processed = 0;
		while (processed < MAX_EVENTS_PER_TICK) {
			Object event;
			synchronized (queueMutex) {
				event = queue.poll();
				if (event != null) {
					queueSize.updateAndGet(value -> Math.max(0, value - 1));
				}
			}
			if (event == null) {
				break;
			}
			processed++;

			if (event instanceof ViewerChatEvent chat) {
				processChat(server, chat);
			} else if (event instanceof ViewerGiftEvent gift) {
				processGift(server, gift);
			}
		}
	}

	private void processChat(MinecraftServer server, ViewerChatEvent event) {
		long gameTime = server.overworld().getGameTime();
		lastViewer = event.viewerId();
		lastEventKind = "chat";
		lastEventGameTime = gameTime;

		if (!ArenaConfig.get().isViewerEventsEnabled()) {
			lastError = "viewer_events_disabled";
			return;
		}

		Country country = parseCountryCommand(event.message());
		if (country == null) {
			lastError = "unknown_or_missing_country_command";
			return;
		}

		String viewerId = event.viewerId();
		synchronized (countryByViewerMutex) {
			countryByViewer.put(viewerId, country);
		}
		acceptedChatEvents.incrementAndGet();
		lastCountryCode = country.getCode();
		lastGiftSummary = "";
		lastFighterCount = 0;
		lastError = "";
	}

	private void processGift(MinecraftServer server, ViewerGiftEvent event) {
		ArenaConfig config = ArenaConfig.get();
		long gameTime = server.overworld().getGameTime();
		lastViewer = event.viewerId();
		lastEventKind = "gift";
		lastEventGameTime = gameTime;

		if (!config.isViewerEventsEnabled()) {
			rejectedGifts.incrementAndGet();
			lastError = "viewer_events_disabled";
			return;
		}

		if (event.coins() <= 0) {
			rejectedGifts.incrementAndGet();
			lastError = "coins_le_zero";
			return;
		}

		Country country;
		synchronized (countryByViewerMutex) {
			country = countryByViewer.get(event.viewerId());
		}
		if (country == null) {
			rejectedGifts.incrementAndGet();
			lastError = "country_not_selected";
			lastCountryCode = "";
			lastGiftSummary = event.coins() + " coins (rejected)";
			lastFighterCount = 0;
			return;
		}

		String eventId = event.eventId();
		boolean hasEventId = eventId != null && !eventId.isBlank();
		if (!hasEventId) {
			giftsWithoutEventId.incrementAndGet();
		} else {
			if (!tryClaimGiftEventId(eventId, gameTime, config.getViewerEventDedupSeconds())) {
				duplicateGifts.incrementAndGet();
				lastError = "duplicate_eventId";
				lastCountryCode = country.getCode();
				lastGiftSummary = event.coins() + " coins eventId=" + eventId;
				lastFighterCount = 0;
				return;
			}
		}

		ServerLevel level = ArenaSpawns.resolveFightLevel(server, server.overworld());
		Vec3 origin = resolveGiftOrigin(server, level);
		ArenaMatchManager.get().handleGift(server, level, origin, country, event.coins());
		acceptedGifts.incrementAndGet();
		lastCountryCode = country.getCode();
		lastFighterCount = event.coins();
		lastGiftSummary = event.coins() + " coins"
				+ (hasEventId ? (" eventId=" + eventId) : " (no eventId)");
		lastError = "";
	}

	private static Vec3 resolveGiftOrigin(MinecraftServer server, ServerLevel level) {
		Vec3 matchCenter = ArenaMatchManager.get().getMatchCenter();
		Vec3 resolved = ArenaSpawns.resolveMatchCenter(server, matchCenter);
		if (!resolved.equals(Vec3.ZERO)) {
			return resolved;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.serverLevel() == level) {
				return player.position();
			}
		}
		BlockPos spawn = level.getSharedSpawnPos();
		return new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
	}

	private boolean tryClaimGiftEventId(String eventId, long gameTime, int dedupSeconds) {
		long ttlTicks = Math.max(1L, dedupSeconds) * 20L;
		synchronized (processedGiftIds) {
			Long previous = processedGiftIds.get(eventId);
			if (previous != null && gameTime - previous < ttlTicks) {
				return false;
			}
			processedGiftIds.put(eventId, gameTime);
			while (processedGiftIds.size() > MAX_DEDUP_IDS) {
				Iterator<String> it = processedGiftIds.keySet().iterator();
				if (!it.hasNext()) {
					break;
				}
				it.next();
				it.remove();
			}
			return true;
		}
	}

	private void cleanupDedupCache(long gameTime) {
		long ttlTicks = Math.max(1L, ArenaConfig.get().getViewerEventDedupSeconds()) * 20L;
		synchronized (processedGiftIds) {
			Iterator<Map.Entry<String, Long>> it = processedGiftIds.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, Long> entry = it.next();
				if (gameTime - entry.getValue() >= ttlTicks) {
					it.remove();
				}
			}
			while (processedGiftIds.size() > MAX_DEDUP_IDS) {
				Iterator<String> keys = processedGiftIds.keySet().iterator();
				if (!keys.hasNext()) {
					break;
				}
				keys.next();
				keys.remove();
			}
		}
	}

	static Country parseCountryCommand(String message) {
		if (message == null) {
			return null;
		}
		String trimmed = message.trim().toLowerCase(Locale.ROOT);
		if (trimmed.startsWith("!") && trimmed.length() > 1) {
			return Country.byId(trimmed.substring(1));
		}
		return null;
	}

	private static String normalizeEventId(String eventId) {
		if (eventId == null) {
			return null;
		}
		String trimmed = eventId.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static int enqueueChatCommand(CommandSourceStack source, String viewerId, String message) {
		boolean ok = INSTANCE.enqueueChat(viewerId, viewerId, message, null);
		if (ok) {
			source.sendSuccess(() -> Component.literal(
					"Chat-событие поставлено в очередь: " + viewerId + " → " + message), false);
			return 1;
		}
		source.sendFailure(Component.literal("Очередь зрительских событий переполнена."));
		return 0;
	}

	private static int enqueueGiftCommand(
			CommandSourceStack source,
			String viewerId,
			int coins,
			String eventId) {
		boolean ok = INSTANCE.enqueueGift(viewerId, viewerId, coins, eventId);
		if (ok) {
			String idPart = (eventId == null || eventId.isBlank()) ? "без eventId" : "eventId=" + eventId;
			source.sendSuccess(() -> Component.literal(
					"Gift-событие поставлено в очередь: " + viewerId + " +" + coins + " (" + idPart + ")"), false);
			return 1;
		}
		source.sendFailure(Component.literal("Очередь зрительских событий переполнена."));
		return 0;
	}
}
