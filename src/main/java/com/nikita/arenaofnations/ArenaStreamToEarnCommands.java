package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Command bridge for StreamToEarn. Parses server console payloads and forwards
 * them into {@link ArenaViewerEventManager} without touching match logic.
 */
public final class ArenaStreamToEarnCommands {
	public static final String SEPARATOR = "|||";

	private static final int MAX_PAYLOAD_LENGTH = 1000;
	private static final int MAX_VIEWER_ID_LENGTH = 100;
	private static final int MAX_MESSAGE_LENGTH = 500;
	private static final int MAX_EVENT_ID_LENGTH = 200;
	private static final int MIN_COINS = 1;
	private static final int MAX_COINS = 1_000_000;

	private static final AtomicLong acceptedChatCommands = new AtomicLong();
	private static final AtomicLong acceptedGiftCommands = new AtomicLong();
	private static final AtomicLong rejectedCommands = new AtomicLong();
	private static final AtomicReference<String> lastResult = new AtomicReference<>("NONE");
	private static final AtomicReference<String> lastRejectReason = new AtomicReference<>("");

	private ArenaStreamToEarnCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_s2e_chat")
					.requires(source -> source.hasPermission(2))
					.executes(context -> showChatHelp(context.getSource()))
					.then(Commands.argument("payload", StringArgumentType.greedyString())
							.executes(context -> handleChatCommand(
									context.getSource(),
									StringArgumentType.getString(context, "payload")))));

			dispatcher.register(Commands.literal("arena_s2e_gift")
					.requires(source -> source.hasPermission(2))
					.executes(context -> showGiftHelp(context.getSource()))
					.then(Commands.argument("payload", StringArgumentType.greedyString())
							.executes(context -> handleGiftCommand(
									context.getSource(),
									StringArgumentType.getString(context, "payload")))));

			dispatcher.register(Commands.literal("arena_s2e_status")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						MinecraftServer server = context.getSource().getServer();
						context.getSource().sendSuccess(
								() -> Component.literal(buildStatusText(server)), false);
						return 1;
					}));
		});
	}

	/**
	 * Parse and enqueue a StreamToEarn chat payload. Public for automated tests.
	 */
	public static AcceptResult acceptChatPayload(String payload) {
		AcceptResult result = parseAndEnqueueChat(payload);
		recordResult(result, true);
		return result;
	}

	/**
	 * Parse and enqueue a StreamToEarn gift payload. Public for automated tests.
	 */
	public static AcceptResult acceptGiftPayload(String payload) {
		AcceptResult result = parseAndEnqueueGift(payload);
		recordResult(result, false);
		return result;
	}

	public static String buildStatusText() {
		return buildStatusText(null);
	}

	public static String buildStatusText(MinecraftServer server) {
		ArenaConfig config = ArenaConfig.get();
		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		StringBuilder builder = new StringBuilder();
		builder.append("StreamToEarn bridge:\n");
		builder.append("bridge_enabled(config s2e_http_enabled)=").append(config.isS2eHttpEnabled()).append('\n');
		builder.append("viewer_events_enabled=").append(config.isViewerEventsEnabled()).append('\n');
		builder.append("HTTP running=").append(ArenaStreamToEarnHttpBridge.isRunning()).append('\n');
		builder.append("S2E endpoints active=").append(ArenaStreamToEarnHttpBridge.areS2eEndpointsActive()).append('\n');
		builder.append("bind=").append(ArenaStreamToEarnHttpBridge.getBindAddress()).append('\n');
		builder.append("port(effective)=").append(ArenaStreamToEarnHttpBridge.getRunningPort()).append('\n');
		builder.append("port(config s2e_http_port)=").append(ArenaStreamToEarnHttpBridge.getConfiguredPort()).append('\n');
		builder.append("token configured=").append(ArenaStreamToEarnHttpBridge.isTokenConfigured()).append('\n');
		builder.append("endpoints:\n");
		builder.append("  GET  /arena/health\n");
		builder.append("  POST /arena/chat  (header X-Arena-Token)\n");
		builder.append("  POST /arena/gift  (header X-Arena-Token)\n");
		builder.append("  POST /arena/streamtoearn/chat  (body-auth JSON/plain)\n");
		builder.append("  POST /arena/streamtoearn/gift  (body-auth JSON/plain)\n");
		builder.append("received chat (ingress)=").append(acceptedChatCommands.get()).append('\n');
		builder.append("received gift (ingress)=").append(acceptedGiftCommands.get()).append('\n');
		builder.append("rejected (ingress)=").append(rejectedCommands.get()).append('\n');
		builder.append("accepted chat (processed)=").append(viewers.getAcceptedChatEvents()).append('\n');
		builder.append("accepted gifts (processed)=").append(viewers.getAcceptedGifts()).append('\n');
		builder.append("rejected gifts (processed)=").append(viewers.getRejectedGifts()).append('\n');
		builder.append("duplicate gifts=").append(viewers.getDuplicateGifts()).append('\n');
		builder.append("queued events=").append(viewers.getQueueSize()).append('\n');
		builder.append("gifts without eventId=").append(viewers.getGiftsWithoutEventId()).append('\n');
		builder.append("queue overflows=").append(viewers.getQueueOverflows()).append('\n');
		builder.append("last event kind=").append(emptyAsNone(viewers.getLastEventKind())).append('\n');
		builder.append("last event age ticks=");
		long lastGameTime = viewers.getLastEventGameTime();
		if (server != null && lastGameTime >= 0L) {
			builder.append(Math.max(0L, server.overworld().getGameTime() - lastGameTime));
		} else {
			builder.append(lastGameTime < 0L ? "n/a" : String.valueOf(lastGameTime));
		}
		builder.append('\n');
		builder.append("last viewer=").append(emptyAsNone(viewers.getLastViewer())).append('\n');
		builder.append("last gift=").append(emptyAsNone(viewers.getLastGiftSummary())).append('\n');
		builder.append("last country=").append(emptyAsNone(viewers.getLastCountryCode())).append('\n');
		builder.append("last fighter count=").append(viewers.getLastFighterCount()).append('\n');
		builder.append("last error=").append(emptyAsNone(viewers.getLastError())).append('\n');
		builder.append("ingress last result=").append(lastResult.get()).append('\n');
		String reason = lastRejectReason.get();
		builder.append("ingress reject reason=")
				.append(reason == null || reason.isEmpty() ? "нет" : reason);
		return builder.toString();
	}

	private static String emptyAsNone(String value) {
		return value == null || value.isEmpty() ? "нет" : value;
	}

	public static void clearBridgeCounters() {
		acceptedChatCommands.set(0);
		acceptedGiftCommands.set(0);
		rejectedCommands.set(0);
		lastResult.set("NONE");
		lastRejectReason.set("");
	}

	private static AcceptResult parseAndEnqueueChat(String payload) {
		String cleaned = sanitizePayload(payload);
		if (cleaned == null) {
			return AcceptResult.rejected("пустой или недопустимый payload");
		}
		if (cleaned.length() > MAX_PAYLOAD_LENGTH) {
			return AcceptResult.rejected("payload длиннее " + MAX_PAYLOAD_LENGTH + " символов");
		}

		int sep = cleaned.indexOf(SEPARATOR);
		if (sep < 0) {
			return AcceptResult.rejected("ожидался разделитель ||| (формат: viewerId|||message)");
		}

		String viewerId = cleaned.substring(0, sep).trim();
		String message = stripControlChars(cleaned.substring(sep + SEPARATOR.length()));

		if (viewerId.isEmpty()) {
			return AcceptResult.rejected("пустой viewerId");
		}
		if (viewerId.length() > MAX_VIEWER_ID_LENGTH) {
			return AcceptResult.rejected("viewerId длиннее " + MAX_VIEWER_ID_LENGTH + " символов");
		}
		if (message.isEmpty()) {
			return AcceptResult.rejected("пустое message");
		}
		if (message.length() > MAX_MESSAGE_LENGTH) {
			return AcceptResult.rejected("message длиннее " + MAX_MESSAGE_LENGTH + " символов");
		}

		boolean queued = ArenaViewerEventManager.get().enqueueChat(viewerId, viewerId, message, null);
		if (!queued) {
			return AcceptResult.rejected("очередь viewer events переполнена");
		}
		return AcceptResult.ok();
	}

	private static AcceptResult parseAndEnqueueGift(String payload) {
		String cleaned = sanitizePayload(payload);
		if (cleaned == null) {
			return AcceptResult.rejected("пустой или недопустимый payload");
		}
		if (cleaned.length() > MAX_PAYLOAD_LENGTH) {
			return AcceptResult.rejected("payload длиннее " + MAX_PAYLOAD_LENGTH + " символов");
		}

		String[] parts = cleaned.split("\\|\\|\\|", -1);
		if (parts.length != 2 && parts.length != 3) {
			return AcceptResult.rejected("ожидалось 2 или 3 части (viewerId|||coins[|||eventId])");
		}

		String viewerId = parts[0].trim();
		String coinsRaw = stripControlChars(parts[1]).trim();
		String eventId = parts.length == 3 ? stripControlChars(parts[2]).trim() : "";

		if (viewerId.isEmpty()) {
			return AcceptResult.rejected("пустой viewerId");
		}
		if (viewerId.length() > MAX_VIEWER_ID_LENGTH) {
			return AcceptResult.rejected("viewerId длиннее " + MAX_VIEWER_ID_LENGTH + " символов");
		}
		if (coinsRaw.isEmpty()) {
			return AcceptResult.rejected("пустое значение coins");
		}

		int coins;
		try {
			coins = Integer.parseInt(coinsRaw);
		} catch (NumberFormatException e) {
			return AcceptResult.rejected("coins должно быть целым числом");
		}
		if (coins < MIN_COINS || coins > MAX_COINS) {
			return AcceptResult.rejected("coins вне диапазона " + MIN_COINS + "–" + MAX_COINS);
		}

		if (eventId.length() > MAX_EVENT_ID_LENGTH) {
			return AcceptResult.rejected("eventId длиннее " + MAX_EVENT_ID_LENGTH + " символов");
		}
		if (eventId.isEmpty()) {
			eventId = null;
		}

		boolean queued = ArenaViewerEventManager.get().enqueueGift(viewerId, viewerId, coins, eventId);
		if (!queued) {
			return AcceptResult.rejected("очередь viewer events переполнена");
		}
		return AcceptResult.ok();
	}

	private static String sanitizePayload(String payload) {
		if (payload == null) {
			return null;
		}
		String cleaned = stripControlChars(payload);
		if (cleaned.isEmpty()) {
			return null;
		}
		return cleaned;
	}

	private static String stripControlChars(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (!Character.isISOControl(ch)) {
				builder.append(ch);
			}
		}
		return builder.toString();
	}

	private static void recordResult(AcceptResult result, boolean chat) {
		if (result.accepted()) {
			lastResult.set("ACCEPTED");
			lastRejectReason.set("");
			if (chat) {
				acceptedChatCommands.incrementAndGet();
			} else {
				acceptedGiftCommands.incrementAndGet();
			}
		} else {
			lastResult.set("REJECTED");
			lastRejectReason.set(result.reason() == null ? "неизвестная ошибка" : result.reason());
			rejectedCommands.incrementAndGet();
		}
	}

	private static int handleChatCommand(CommandSourceStack source, String payload) {
		AcceptResult result = acceptChatPayload(payload);
		if (result.accepted()) {
			source.sendSuccess(() -> Component.literal("S2E chat принят в очередь."), false);
			return 1;
		}
		source.sendFailure(Component.literal(
				"S2E chat отклонён: " + result.reason()
						+ "\nПример: /arena_s2e_chat user123|||!ru"));
		return 0;
	}

	private static int handleGiftCommand(CommandSourceStack source, String payload) {
		AcceptResult result = acceptGiftPayload(payload);
		if (result.accepted()) {
			source.sendSuccess(() -> Component.literal("S2E gift принят в очередь."), false);
			return 1;
		}
		source.sendFailure(Component.literal(
				"S2E gift отклонён: " + result.reason()
						+ "\nПример: /arena_s2e_gift user123|||50"
						+ "\nИли: /arena_s2e_gift user123|||50|||gift_123"));
		return 0;
	}

	private static int showChatHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Использование: /arena_s2e_chat <viewerId>|||<message>\n"
						+ "Пример: /arena_s2e_chat user123|||!ru"), false);
		return 1;
	}

	private static int showGiftHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"Использование: /arena_s2e_gift <viewerId>|||<coins>[|||<eventId>]\n"
						+ "Пример: /arena_s2e_gift user123|||50\n"
						+ "Или: /arena_s2e_gift user123|||50|||gift_123"), false);
		return 1;
	}

	/**
	 * Result of accepting a StreamToEarn command payload.
	 */
	public record AcceptResult(boolean accepted, String reason) {
		public static AcceptResult ok() {
			return new AcceptResult(true, null);
		}

		public static AcceptResult rejected(String reason) {
			return new AcceptResult(false, reason);
		}
	}
}
