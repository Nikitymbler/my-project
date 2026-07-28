package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaHudSnapshot;
import com.nikita.arenaofnations.network.ArenaHudSnapshotPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-thread snapshot holder. A missing packet for too long hides stale round data.
 */
public final class ArenaRoundHudClient {
	private static final long STALE_MS = 5_000L;

	private static volatile ArenaHudSnapshot latest = ArenaHudSnapshot.EMPTY;
	private static volatile long receiveTimestampMs;

	private ArenaRoundHudClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ArenaHudSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> setSnapshot(payload.snapshot())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	public static void setSnapshot(ArenaHudSnapshot snapshot) {
		latest = snapshot == null ? ArenaHudSnapshot.EMPTY : snapshot;
		receiveTimestampMs = System.currentTimeMillis();
	}

	public static void clear() {
		latest = ArenaHudSnapshot.EMPTY;
		receiveTimestampMs = 0L;
	}

	public static ArenaHudSnapshot getSnapshotIfFresh() {
		if (receiveTimestampMs == 0L || System.currentTimeMillis() - receiveTimestampMs > STALE_MS) {
			return ArenaHudSnapshot.EMPTY;
		}
		return latest;
	}
}
