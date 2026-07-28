package com.nikita.arenaofnations;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Local StreamToEarn ingress test without live TikTok:
 * {@link ArenaStreamToEarnCommands#acceptChatPayload}/{@link ArenaStreamToEarnCommands#acceptGiftPayload}
 * (same path HTTP body-auth ends in) → queue → server tick → {@link ArenaMatchManager#handleGift}.
 */
public final class ArenaS2eLocalGiftTest {
	private static final ArenaS2eLocalGiftTest INSTANCE = new ArenaS2eLocalGiftTest();

	private static final String VIEWER = "s2e_local_viewer";
	private static final String EVENT_ID = "s2e_local_gift_event_1";
	private static final int COINS = 10;

	private boolean running;
	private Stage stage = Stage.IDLE;
	private UUID playerId;
	private String levelKey = "";
	private int waitTicks;
	private String lastFailure = "";
	private boolean finishedPass;
	private long acceptedBeforeDuplicate;
	private long duplicatesBefore;

	private ArenaS2eLocalGiftTest() {
	}

	public static ArenaS2eLocalGiftTest get() {
		return INSTANCE;
	}

	public boolean isRunning() {
		return running;
	}

	public void cancel() {
		if (!running) {
			return;
		}
		running = false;
		lastFailure = "cancelled";
		stage = Stage.IDLE;
	}

	public String start(MinecraftServer server, ServerLevel level, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		ArenaViewerEventManager.get().clearTransientState();
		ArenaStreamToEarnCommands.clearBridgeCounters();

		this.running = true;
		this.finishedPass = false;
		this.lastFailure = "";
		this.playerId = playerId;
		this.levelKey = level.dimension().location().toString();
		this.waitTicks = 0;
		this.stage = Stage.CHAT;

		ArenaStreamToEarnCommands.AcceptResult chat =
				ArenaStreamToEarnCommands.acceptChatPayload(VIEWER + "|||!ru");
		if (!chat.accepted()) {
			return finishFail(server, "CHAT_INGRESS", "chat rejected: " + chat.reason());
		}
		return "s2e_local_gift started: ingress chat !ru → gift coins=10 → dedup.";
	}

	public void tick(MinecraftServer server) {
		if (!running || finishedPass) {
			return;
		}
		waitTicks++;
		if (waitTicks > 100) {
			finishFail(server, stage.name(), "timeout");
			return;
		}

		ServerLevel level = resolveLevel(server);
		if (level == null) {
			finishFail(server, stage.name(), "level missing");
			return;
		}

		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		ArenaMatchManager match = ArenaMatchManager.get();

		switch (stage) {
			case CHAT -> {
				if (viewers.getSelectedCountry(VIEWER) != Country.RU) {
					if (waitTicks < 5) {
						return;
					}
					finishFail(server, "CHAT", "viewer did not select RU");
					return;
				}
				ArenaStreamToEarnCommands.AcceptResult gift =
						ArenaStreamToEarnCommands.acceptGiftPayload(VIEWER + "|||" + COINS + "|||" + EVENT_ID);
				if (!gift.accepted()) {
					finishFail(server, "GIFT_INGRESS", "gift rejected: " + gift.reason());
					return;
				}
				stage = Stage.GIFT;
				waitTicks = 0;
			}
			case GIFT -> {
				if (viewers.getAcceptedGifts() < 1) {
					if (waitTicks < 5) {
						return;
					}
					finishFail(server, "GIFT", "gift not processed; lastError=" + viewers.getLastError());
					return;
				}
				int total = match.countLivingFightersUncached(level, Country.RU) + match.getReserveSize(Country.RU);
				if (total != COINS) {
					finishFail(server, "GIFT", "fighters living+reserve=" + total + " expected " + COINS);
					return;
				}
				acceptedBeforeDuplicate = viewers.getAcceptedGifts();
				duplicatesBefore = viewers.getDuplicateGifts();
				ArenaStreamToEarnCommands.AcceptResult dup =
						ArenaStreamToEarnCommands.acceptGiftPayload(VIEWER + "|||" + COINS + "|||" + EVENT_ID);
				if (!dup.accepted()) {
					finishFail(server, "DEDUP_INGRESS", "duplicate payload rejected at ingress: " + dup.reason());
					return;
				}
				stage = Stage.DEDUP;
				waitTicks = 0;
			}
			case DEDUP -> {
				if (viewers.getDuplicateGifts() <= duplicatesBefore && waitTicks < 5) {
					return;
				}
				if (viewers.getDuplicateGifts() <= duplicatesBefore) {
					finishFail(server, "DEDUP", "duplicate eventId not detected");
					return;
				}
				if (viewers.getAcceptedGifts() != acceptedBeforeDuplicate) {
					finishFail(server, "DEDUP", "accepted gifts grew after duplicate");
					return;
				}
				int total = match.countLivingFightersUncached(level, Country.RU) + match.getReserveSize(Country.RU);
				if (total != COINS) {
					finishFail(server, "DEDUP", "fighters after dedup=" + total + " expected " + COINS);
					return;
				}
				finishPass(server);
			}
			default -> {
			}
		}
	}

	public String statusReport(MinecraftServer server) {
		StringBuilder builder = new StringBuilder("S2E local gift status:\n");
		builder.append("running=").append(running).append('\n');
		builder.append("result=").append(finishedPass ? "PASS" : (lastFailure.isEmpty() ? (running ? "RUNNING" : "IDLE") : "FAIL")).append('\n');
		builder.append("stage=").append(stage).append('\n');
		if (!lastFailure.isEmpty()) {
			builder.append("reason=").append(lastFailure).append('\n');
		}
		builder.append(ArenaStreamToEarnCommands.buildStatusText(server));
		return builder.toString();
	}

	private void finishPass(MinecraftServer server) {
		finishedPass = true;
		running = false;
		stage = Stage.DONE;
		lastFailure = "";
		ArenaTestScenarioCommands.onLifecycleFinished();
		notifyPlayer(server, "S2E LOCAL GIFT: PASS");
	}

	private String finishFail(MinecraftServer server, String stageName, String reason) {
		finishedPass = false;
		running = false;
		stage = Stage.DONE;
		lastFailure = reason;
		ArenaTestScenarioCommands.onLifecycleFinished();
		String message = "S2E LOCAL GIFT: FAILED\nstage=" + stageName + "\nreason=" + reason;
		notifyPlayer(server, message);
		return message;
	}

	private ServerLevel resolveLevel(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(levelKey)) {
				return level;
			}
		}
		return server.overworld();
	}

	private void notifyPlayer(MinecraftServer server, String text) {
		if (server == null) {
			return;
		}
		Component message = Component.literal(text);
		ServerPlayer player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
		if (player != null) {
			player.sendSystemMessage(message);
		} else {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
		ArenaOfNations.LOGGER.info(text.replace("\n", " | "));
	}

	private enum Stage {
		IDLE,
		CHAT,
		GIFT,
		DEDUP,
		DONE
	}
}
