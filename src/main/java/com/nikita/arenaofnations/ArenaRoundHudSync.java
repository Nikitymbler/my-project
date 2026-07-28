package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.nikita.arenaofnations.network.ArenaHudSnapshotPayload;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Synchronizes a compact round snapshot for the custom client HUD.
 */
public final class ArenaRoundHudSync {
	private static final ArenaRoundHudSync INSTANCE = new ArenaRoundHudSync();

	private final Set<UUID> clientHudDisabledPlayers = new HashSet<>();
	private int tickCounter;
	private ArenaHudSnapshot lastSent = ArenaHudSnapshot.EMPTY;
	private boolean sentNonIdle;

	private ArenaRoundHudSync() {
	}

	public static void registerCommon() {
		PayloadTypeRegistry.playS2C().register(ArenaHudSnapshotPayload.TYPE, ArenaHudSnapshotPayload.STREAM_CODEC);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(INSTANCE::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("arena_hud_status")
						.requires(source -> source.hasPermission(2))
						.executes(context -> showStatus(context.getSource()))));
	}

	public static boolean isClientHudEnabled(ServerPlayer player) {
		return player != null && !INSTANCE.clientHudDisabledPlayers.contains(player.getUUID());
	}

	public static int setClientHud(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (enabled) {
			INSTANCE.clientHudDisabledPlayers.remove(player.getUUID());
			ServerPlayNetworking.send(player, new ArenaHudSnapshotPayload(buildSnapshot(source.getServer())));
			source.sendSuccess(() -> Component.literal("Клиентский HUD арены включён."), false);
		} else {
			INSTANCE.clientHudDisabledPlayers.add(player.getUUID());
			ServerPlayNetworking.send(player, new ArenaHudSnapshotPayload(ArenaHudSnapshot.EMPTY));
			source.sendSuccess(() -> Component.literal("Клиентский HUD арены выключен."), false);
		}
		return 1;
	}

	public static int toggleClientHud(CommandSourceStack source) throws CommandSyntaxException {
		return setClientHud(source, !isClientHudEnabled(source.getPlayerOrException()));
	}

	private void tick(MinecraftServer server) {
		ArenaHudSnapshot snapshot = buildSnapshot(server);
		if (!snapshot.shouldDisplay()) {
			if (sentNonIdle) {
				sendToAll(server, ArenaHudSnapshot.EMPTY);
				lastSent = ArenaHudSnapshot.EMPTY;
				sentNonIdle = false;
			}
			return;
		}

		tickCounter++;
		int updateTicks = Math.max(10, ArenaConfig.get().getHudUpdateTicks());
		if (tickCounter % updateTicks != 0 && hasSameContent(lastSent, snapshot)) {
			return;
		}

		sendToAll(server, snapshot);
		lastSent = snapshot;
		sentNonIdle = true;
	}

	public static ArenaHudSnapshot buildSnapshot(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaMatchState state = match.getState();
		if (state == ArenaMatchState.IDLE) {
			return ArenaHudSnapshot.EMPTY;
		}
		ArenaHudDisplayMode mode = ArenaHudManager.get().getHudMode();

		LinkedHashSet<Country> countries = new LinkedHashSet<>(match.getCurrentRoundCountries());
		countries.addAll(match.getActiveCountries());
		if (state == ArenaMatchState.BATTLE) {
			countries.addAll(ArenaCoreRescueManager.get().getEliminatedCountries());
		}

		Country holder = state == ArenaMatchState.WAITING_FOR_OPPONENT && !countries.isEmpty()
				? countries.iterator().next()
				: null;
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, server.overworld());
		List<ArenaHudCountryState> states = new ArrayList<>(countries.size());
		ArenaCoreRescueManager rescue = ArenaCoreRescueManager.get();
		String rescueCountryCode = null;
		for (Country country : countries) {
			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			boolean eliminated = rescue.isEliminated(country);
			boolean rescuing = !eliminated && rescue.isRescuing(country);
			if (rescuing && rescueCountryCode == null) {
				rescueCountryCode = country.getCode();
			}
			int rescueSeconds = rescuing ? rescue.getRescueRemainingSeconds(server, country) : 0;
			states.add(new ArenaHudCountryState(
					country,
					match.getBaseSlot(country),
					match.getLiveFighterCount(server, country),
					core.getCurrentHealth(),
					core.getMaxHealth(),
					match.getReserveSize(country),
					eliminated,
					rescuing,
					rescueSeconds,
					country == holder,
					fightLevel != null && ArenaCoreManager.get().isCoreProtected(fightLevel, country)));
		}
		states.sort(java.util.Comparator.comparingInt(row -> row.baseSlot() < 0 ? 999 : row.baseSlot()));

		net.minecraft.world.phys.Vec3 center = match.getMatchCenter();
		boolean centerValid = center != null && !center.equals(net.minecraft.world.phys.Vec3.ZERO);
		int cx = centerValid ? (int) Math.floor(center.x) : 0;
		int cy = centerValid ? (int) Math.floor(center.y) : 0;
		int cz = centerValid ? (int) Math.floor(center.z) : 0;

		return new ArenaHudSnapshot(
				state,
				match.getRemainingStateTicks(),
				mode,
				match.getActiveCountries().size(),
				rescueCountryCode,
				cx,
				cy,
				cz,
				centerValid,
				states);
	}

	private static boolean hasSameContent(ArenaHudSnapshot left, ArenaHudSnapshot right) {
		return left.state() == right.state() && left.countries().equals(right.countries());
	}

	private static void sendToAll(MinecraftServer server, ArenaHudSnapshot snapshot) {
		ArenaHudSnapshotPayload payload = new ArenaHudSnapshotPayload(snapshot);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (INSTANCE.clientHudDisabledPlayers.contains(player.getUUID())) {
				continue;
			}
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Push the current round snapshot to all players immediately (test/admin). */
	public static void pushNow(MinecraftServer server) {
		ArenaHudSnapshot snapshot = buildSnapshot(server);
		sendToAll(server, snapshot);
		INSTANCE.lastSent = snapshot;
		INSTANCE.sentNonIdle = snapshot.shouldDisplay();
		INSTANCE.tickCounter = 0;
		ArenaOverlayStateService.pushNow(server);
	}

	private static int showStatus(CommandSourceStack source) {
		ArenaHudSnapshot snapshot = buildSnapshot(source.getServer());
		StringBuilder text = new StringBuilder("Client round HUD:\n")
				.append("состояние=").append(snapshot.state())
				.append(" (").append(snapshot.formatStatusText()).append(")")
				.append("\nтаймер=").append(snapshot.formatTimerMmSs())
				.append("\nmode=").append(snapshot.mode())
				.append("\nactiveCountries=").append(snapshot.activeCountryCount());
		if (snapshot.countries().isEmpty()) {
			text.append("\nстраны: нет");
		} else {
			for (ArenaHudCountryState country : snapshot.countries()) {
				text.append("\n- ").append(country.country().getDisplayName())
						.append(": бойцы=").append(country.aliveFighters())
						.append(", ядро=").append(ArenaCoreManager.formatHealth(country.coreHealth()))
						.append('/').append(ArenaCoreManager.formatHealth(country.coreMaxHealth()))
						.append(", резерв=").append(country.reserveCount())
						.append(", защита=").append(country.coreProtected() ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
						.append(", исключена=").append(country.eliminated())
						.append(", спасение=").append(country.rescuing())
						.append(country.rescuing() ? " (" + country.rescueSecondsRemaining() + "с)" : "")
						.append(", holder=").append(country.holder());
			}
		}
		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return 1;
	}
}
