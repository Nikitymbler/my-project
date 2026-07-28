package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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
						context.getSource().sendSuccess(
								() -> Component.literal(buildStatusText()), false);
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
		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		StringBuilder builder = new StringBuilder();
		builder.append("StreamToEarn bridge:\n");
		builder.append("viewer_events_enabled=").append(ArenaConfig.get().isViewerEventsEnabled()).append('\n');
		builder.append("принятые chat-команды=").append(acceptedChatCommands.get()).append('\n');
		builder.append("принятые gift-команды=").append(acceptedGiftCommands.get()).append('\n');
		builder.append("отклонённые команды=").append(rejectedCommands.get()).append('\n');
		builder.append("очередь viewer events=").append(viewers.getQueueSize()).append('\n');
		builder.append("события без eventId=").append(viewers.getGiftsWithoutEventId()).append('\n');
		builder.append("последний результат=").append(lastResult.get()).append('\n');
		String reason = lastRejectReason.get();
		builder.append("причина отклонения=")
				.append(reason == null || reason.isEmpty() ? "нет" : reason)
				.append('\n');
		builder.append("HTTP running=").append(ArenaStreamToEarnHttpBridge.isRunning()).append('\n');
		builder.append("HTTP port=").append(ArenaStreamToEarnHttpBridge.getConfiguredPort()).append('\n');
		builder.append("HTTP token configured=").append(ArenaStreamToEarnHttpBridge.isTokenConfigured());
		return builder.toString();
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
