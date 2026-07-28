package com.nikita.arenaofnations;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Legacy server-side match HUD using Minecraft BossBars.
 * Off by default — normal play uses the client round HUD ({@link ArenaRoundHudSync}).
 * Enable only for debug via {@code /arena_hud bossbar on}.
 */
public final class ArenaHudManager {
	private static final ArenaHudManager INSTANCE = new ArenaHudManager();
	private static final int ELIMINATION_HIDE_TICKS = 60;

	private final ServerBossEvent mainBar = new ServerBossEvent(
			Component.literal("Arena of Nations"),
			BossEvent.BossBarColor.WHITE,
			BossEvent.BossBarOverlay.PROGRESS);

	private final EnumMap<Country, ServerBossEvent> countryBars = new EnumMap<>(Country.class);
	private final EnumMap<Country, PendingHide> pendingHides = new EnumMap<>(Country.class);
	private final EnumMap<Country, Boolean> eliminatedVisible = new EnumMap<>(Country.class);

	/** Debug-only BossBar overlay; default off so it never stacks on the client HUD. */
	private boolean bossBarEnabled = false;
	private ArenaHudDisplayMode hudMode = ArenaConfig.get().getDefaultHudMode();
	private int roundGeneration = 0;
	private int tickCounter = 0;

	private ArenaHudManager() {
		for (Country country : Country.values()) {
			countryBars.put(country, new ServerBossEvent(
					Component.literal(country.getDisplayName()),
					bossColor(country),
					BossEvent.BossBarOverlay.PROGRESS));
			eliminatedVisible.put(country, false);
		}
		mainBar.setVisible(false);
		for (ServerBossEvent bar : countryBars.values()) {
			bar.setVisible(false);
		}
	}

	public static ArenaHudManager get() {
		return INSTANCE;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.tick(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> INSTANCE.clearAll(server));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_hud")
					.executes(context -> showHudHelp(context.getSource()))
					.then(Commands.literal("on").executes(context -> ArenaRoundHudSync.setClientHud(context.getSource(), true)))
					.then(Commands.literal("off").executes(context -> ArenaRoundHudSync.setClientHud(context.getSource(), false)))
					.then(Commands.literal("toggle").executes(context -> ArenaRoundHudSync.toggleClientHud(context.getSource())))
					.then(Commands.literal("status").executes(context -> showHudStatus(context.getSource())))
					.then(Commands.literal("mode")
							.then(Commands.argument("value", StringArgumentType.word())
									.executes(context -> setHudMode(
											context.getSource(),
											StringArgumentType.getString(context, "value")))))
					.then(Commands.literal("bossbar")
							.then(Commands.literal("on").executes(context -> setBossBar(context.getSource(), true)))
							.then(Commands.literal("off").executes(context -> setBossBar(context.getSource(), false)))
							.then(Commands.literal("status").executes(context -> showBossBarStatus(context.getSource())))));

			dispatcher.register(Commands.literal("arena_hud_debug")
					.requires(source -> source.hasPermission(2))
					.executes(context -> showHudDebug(context.getSource())));
		});
	}

	public boolean isBossBarEnabled() {
		return bossBarEnabled;
	}

	public ArenaHudDisplayMode getHudMode() {
		return hudMode;
	}

	public void clearAll(MinecraftServer server) {
		roundGeneration++;
		pendingHides.clear();
		for (Country country : Country.values()) {
			eliminatedVisible.put(country, false);
		}
		hideAllBars();
		if (server != null) {
			removeAllViewers(server);
		} else {
			mainBar.removeAllPlayers();
			for (ServerBossEvent bar : countryBars.values()) {
				bar.removeAllPlayers();
			}
		}
	}

	public void onEnterBreak() {
		roundGeneration++;
		pendingHides.clear();
		for (Country country : Country.values()) {
			eliminatedVisible.put(country, false);
			ServerBossEvent bar = countryBars.get(country);
			bar.setVisible(false);
			bar.removeAllPlayers();
		}
	}

	public void onCountryEliminated(Country country) {
		eliminatedVisible.put(country, true);
		pendingHides.put(country, new PendingHide(ELIMINATION_HIDE_TICKS, roundGeneration));
	}

	public String buildDebugText(MinecraftServer server) {
		ArenaConfig config = ArenaConfig.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		int viewers = countUniqueViewers();
		HudMode mode = resolveHudMode(match.getState());

		StringBuilder builder = new StringBuilder();
		builder.append("HUD debug:\n");
		builder.append("bossbar_enabled=").append(bossBarEnabled).append('\n');
		builder.append("hud_update_ticks=").append(config.getHudUpdateTicks()).append('\n');
		builder.append("hud_view_distance=").append(config.getHudViewDistance()).append('\n');
		builder.append("состояние матча=").append(match.getState()).append('\n');
		builder.append("режим BossBar=").append(mode).append('\n');
		builder.append("игроков с BossBar=").append(viewers).append('\n');
		builder.append("главная полоса visible=").append(mainBar.isVisible())
				.append(", progress=").append(formatProgress(mainBar.getProgress()))
				.append(", name=\"").append(mainBar.getName().getString()).append("\"\n");
		builder.append("порядок видимых полос:");
		int order = 1;
		boolean any = false;
		if (mainBar.isVisible()) {
			builder.append('\n').append(order++).append(". MAIN: \"").append(mainBar.getName().getString()).append('"');
			any = true;
		}
		for (Country country : Country.values()) {
			ServerBossEvent bar = countryBars.get(country);
			if (!bar.isVisible()) {
				continue;
			}
			builder.append('\n')
					.append(order++)
					.append(". ")
					.append(country.getDisplayName())
					.append(": \"")
					.append(bar.getName().getString())
					.append('"');
			any = true;
		}
		if (!any) {
			builder.append("\nнет");
		}
		builder.append("\nполосы стран:");
		for (Country country : Country.values()) {
			ServerBossEvent bar = countryBars.get(country);
			builder.append('\n')
					.append("- ")
					.append(country.getDisplayName())
					.append(": visible=").append(bar.isVisible())
					.append(", progress=").append(formatProgress(bar.getProgress()))
					.append(", name=\"").append(bar.getName().getString()).append('"');
		}
		return builder.toString();
	}

	private void tick(MinecraftServer server) {
		tickPendingHides();

		if (!bossBarEnabled) {
			if (mainBar.isVisible() || anyCountryVisible()) {
				hideAllBars();
				removeAllViewers(server);
			}
			return;
		}

		ArenaConfig config = ArenaConfig.get();
		tickCounter++;
		if (tickCounter % Math.max(1, config.getHudUpdateTicks()) != 0) {
			return;
		}

		updateHud(server);
	}

	private void tickPendingHides() {
		Iterator<Map.Entry<Country, PendingHide>> it = pendingHides.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Country, PendingHide> entry = it.next();
			PendingHide hide = entry.getValue();
			if (hide.generation != roundGeneration) {
				it.remove();
				continue;
			}
			hide.ticksRemaining--;
			if (hide.ticksRemaining <= 0) {
				Country country = entry.getKey();
				eliminatedVisible.put(country, false);
				ServerBossEvent bar = countryBars.get(country);
				bar.setVisible(false);
				bar.removeAllPlayers();
				it.remove();
			}
		}
	}

	private void updateHud(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaMatchState state = match.getState();

		if (state == ArenaMatchState.IDLE) {
			hideAllBars();
			removeAllViewers(server);
			return;
		}

		Set<ServerPlayer> viewers = collectViewers(server);

		if (state == ArenaMatchState.WAITING_FOR_OPPONENT) {
			hideCountryBars();
			updateMainBar(match, state);
			syncBarViewers(mainBar, viewers);
			return;
		}

		if (state == ArenaMatchState.BREAK) {
			hideCountryBars();
			updateMainBar(match, state);
			syncBarViewers(mainBar, viewers);
			return;
		}

		// BATTLE: Minecraft shows at most 4 BossBars — hide main, show up to 4 country bars.
		mainBar.setVisible(false);
		mainBar.removeAllPlayers();

		EnumMap<Country, Integer> liveCounts = match.countLivingFightersByCountry(server);
		Set<Country> roundCountries = new HashSet<>(match.getCurrentRoundCountries());
		for (Country country : Country.values()) {
			if (Boolean.TRUE.equals(eliminatedVisible.get(country))) {
				roundCountries.add(country);
			}
		}

		boolean timerPlaced = false;
		for (Country country : Country.values()) {
			ServerBossEvent bar = countryBars.get(country);
			boolean show = roundCountries.contains(country);
			if (!show) {
				bar.setVisible(false);
				bar.removeAllPlayers();
				continue;
			}

			boolean includeTimer = !timerPlaced;
			updateCountryBar(
					server,
					country,
					bar,
					liveCounts.getOrDefault(country, 0),
					match.getReserveCount(country),
					includeTimer,
					match);
			bar.setVisible(true);
			timerPlaced = true;
		}

		syncViewers(viewers);
	}

	private void hideCountryBars() {
		for (Country country : Country.values()) {
			ServerBossEvent bar = countryBars.get(country);
			bar.setVisible(false);
			bar.removeAllPlayers();
		}
	}

	private void updateMainBar(ArenaMatchManager match, ArenaMatchState state) {
		ArenaConfig config = ArenaConfig.get();
		int remainingTicks = match.getRemainingStateTicks();
		int remainingSeconds = match.getRemainingSeconds();

		switch (state) {
			case WAITING_FOR_OPPONENT -> {
				Country holder = match.getCurrentRoundCountries().stream().findFirst().orElse(null);
				String name = holder == null
						? "Ожидание соперника | " + remainingSeconds + " сек."
						: "Ожидание соперника | " + holder.getDisplayName() + " | " + remainingSeconds + " сек.";
				mainBar.setName(Component.literal(name));
				mainBar.setColor(BossEvent.BossBarColor.WHITE);
				mainBar.setProgress(ratio(remainingTicks, config.getWaitingSeconds() * 20));
				mainBar.setVisible(true);
			}
			case BREAK -> {
				if (match.wasLastRoundTie() || match.getLastRoundWinner() == null) {
					mainBar.setName(Component.literal("Ничья | Новый раунд через " + remainingSeconds + " сек."));
					mainBar.setColor(BossEvent.BossBarColor.WHITE);
				} else {
					mainBar.setName(Component.literal(
							"Победила " + match.getLastRoundWinner().getDisplayName()
									+ " | Новый раунд через " + remainingSeconds + " сек."));
					mainBar.setColor(BossEvent.BossBarColor.GREEN);
				}
				mainBar.setProgress(ratio(remainingTicks, config.getBreakSeconds() * 20));
				mainBar.setVisible(true);
			}
			default -> {
				mainBar.setVisible(false);
				mainBar.removeAllPlayers();
			}
		}
	}

	private void updateCountryBar(
			MinecraftServer server,
			Country country,
			ServerBossEvent bar,
			int liveFighters,
			int reserve,
			boolean includeBattleTimer,
			ArenaMatchManager match) {
		ArenaCoreRescueManager rescue = ArenaCoreRescueManager.get();
		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		String battlePrefix = includeBattleTimer
				? "БИТВА " + formatClock(match.getRemainingSeconds()) + " | "
				: "";

		if (Boolean.TRUE.equals(eliminatedVisible.get(country)) || rescue.isEliminated(country)) {
			bar.setName(Component.literal(battlePrefix + country.getDisplayName() + " | ИСКЛЮЧЕНА"));
			bar.setColor(BossEvent.BossBarColor.RED);
			bar.setProgress(0.0F);
			return;
		}

		if (rescue.isRescuing(country)) {
			int seconds = rescue.getRescueRemainingSeconds(server, country);
			int totalTicks = Math.max(1, ArenaConfig.get().getCoreRescueSeconds() * 20);
			long remainingTicks = rescue.getRescueRemainingTicks(server, country);
			bar.setName(Component.literal(
					battlePrefix + country.getDisplayName()
							+ " | ВОССТАНОВЛЕНИЕ " + seconds + " сек."));
			bar.setColor(BossEvent.BossBarColor.RED);
			bar.setProgress(ratio(remainingTicks, totalTicks));
			return;
		}

		if (core.getCurrentHealth() <= 0.0F || core.isDestroyed()) {
			bar.setName(Component.literal(
					battlePrefix + country.getDisplayName()
							+ " | ЯДРО СБИТО | Бойцы " + liveFighters
							+ " | Резерв " + reserve));
			bar.setColor(BossEvent.BossBarColor.RED);
			bar.setProgress(0.0F);
			return;
		}

		int hp = Math.round(core.getCurrentHealth());
		int maxHp = Math.round(core.getMaxHealth());
		bar.setName(Component.literal(
				battlePrefix
						+ country.getDisplayName()
						+ " | Ядро " + hp + "/" + maxHp
						+ " | Бойцы " + liveFighters
						+ " | Резерв " + reserve));
		bar.setColor(bossColor(country));
		bar.setProgress(ratio(core.getCurrentHealth(), Math.max(1.0F, core.getMaxHealth())));
	}

	private static HudMode resolveHudMode(ArenaMatchState state) {
		return switch (state) {
			case WAITING_FOR_OPPONENT -> HudMode.WAITING_MAIN;
			case BATTLE -> HudMode.BATTLE_COUNTRIES;
			case BREAK -> HudMode.BREAK_MAIN;
			case IDLE -> HudMode.HIDDEN;
		};
	}

	private Set<ServerPlayer> collectViewers(MinecraftServer server) {
		Set<ServerPlayer> viewers = new HashSet<>();
		ArenaConfig config = ArenaConfig.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		if (match.getState() == ArenaMatchState.IDLE) {
			return viewers;
		}

		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		boolean arenaReady = setup != null && setup.isConfigured() && setup.isBuilt();
		ServerLevel arenaLevel = ArenaBuildManager.resolveArenaLevel(server);
		BlockPos centerPos = arenaReady ? setup.getCenter() : null;
		Vec3 matchCenter = match.getMatchCenter();
		ServerLevel fallbackLevel = ArenaSpawns.resolveFightLevel(server, server.overworld());
		double viewDistSq = (double) config.getHudViewDistance() * config.getHudViewDistance();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (arenaReady && arenaLevel != null && centerPos != null) {
				if (player.serverLevel() != arenaLevel) {
					continue;
				}
				double dx = player.getX() - (centerPos.getX() + 0.5);
				double dy = player.getY() - centerPos.getY();
				double dz = player.getZ() - (centerPos.getZ() + 0.5);
				if (dx * dx + dy * dy + dz * dz > viewDistSq) {
					continue;
				}
				viewers.add(player);
				continue;
			}

			// Arena not configured: show HUD to players in the match dimension.
			if (player.serverLevel() == fallbackLevel) {
				if (!matchCenter.equals(Vec3.ZERO)) {
					double dx = player.getX() - matchCenter.x;
					double dy = player.getY() - matchCenter.y;
					double dz = player.getZ() - matchCenter.z;
					if (dx * dx + dy * dy + dz * dz > viewDistSq) {
						continue;
					}
				}
				viewers.add(player);
			}
		}

		return viewers;
	}

	private void syncViewers(Set<ServerPlayer> viewers) {
		if (mainBar.isVisible()) {
			syncBarViewers(mainBar, viewers);
		} else {
			mainBar.removeAllPlayers();
		}
		for (ServerBossEvent bar : countryBars.values()) {
			if (bar.isVisible()) {
				syncBarViewers(bar, viewers);
			} else {
				bar.removeAllPlayers();
			}
		}
	}

	private void syncBarViewers(ServerBossEvent bar, Set<ServerPlayer> desired) {
		for (ServerPlayer player : Set.copyOf(bar.getPlayers())) {
			if (!desired.contains(player)) {
				bar.removePlayer(player);
			}
		}
		for (ServerPlayer player : desired) {
			if (!bar.getPlayers().contains(player)) {
				bar.addPlayer(player);
			}
		}
	}

	private void hideAllBars() {
		mainBar.setVisible(false);
		mainBar.setProgress(0.0F);
		mainBar.setName(Component.literal("Arena of Nations"));
		for (ServerBossEvent bar : countryBars.values()) {
			bar.setVisible(false);
			bar.setProgress(0.0F);
		}
	}

	private void removeAllViewers(MinecraftServer server) {
		mainBar.removeAllPlayers();
		for (ServerBossEvent bar : countryBars.values()) {
			bar.removeAllPlayers();
		}
	}

	private boolean anyCountryVisible() {
		for (ServerBossEvent bar : countryBars.values()) {
			if (bar.isVisible()) {
				return true;
			}
		}
		return false;
	}

	private int countUniqueViewers() {
		Set<UUID> ids = new HashSet<>();
		for (ServerPlayer player : mainBar.getPlayers()) {
			ids.add(player.getUUID());
		}
		for (ServerBossEvent bar : countryBars.values()) {
			for (ServerPlayer player : bar.getPlayers()) {
				ids.add(player.getUUID());
			}
		}
		return ids.size();
	}

	private static BossEvent.BossBarColor bossColor(Country country) {
		return switch (Math.floorMod(country.ordinal(), 4)) {
			case 0 -> BossEvent.BossBarColor.RED;
			case 1 -> BossEvent.BossBarColor.YELLOW;
			case 2 -> BossEvent.BossBarColor.BLUE;
			default -> BossEvent.BossBarColor.GREEN;
		};
	}

	private static float ratio(double current, double max) {
		if (max <= 0.0) {
			return 0.0F;
		}
		return (float) Math.max(0.0, Math.min(1.0, current / max));
	}

	private static String formatClock(int totalSeconds) {
		int minutes = Math.max(0, totalSeconds) / 60;
		int seconds = Math.max(0, totalSeconds) % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}

	private static String formatProgress(float progress) {
		return String.format(java.util.Locale.US, "%.2f", progress);
	}

	private enum HudMode {
		WAITING_MAIN,
		BATTLE_COUNTRIES,
		BREAK_MAIN,
		HIDDEN
	}

	private static int showHudHelp(CommandSourceStack source) {
		boolean clientOn = true;
		if (source.getEntity() instanceof ServerPlayer player) {
			clientOn = ArenaRoundHudSync.isClientHudEnabled(player);
		}
		boolean finalClientOn = clientOn;
		boolean bossBar = INSTANCE.bossBarEnabled;
		source.sendSuccess(() -> Component.literal(
				"Client HUD: " + (finalClientOn ? "включён" : "выключен")
						+ "\nHUD mode: " + INSTANCE.hudMode
						+ "\nBossBar (отладка): " + (bossBar ? "включён" : "выключен")
						+ "\n/arena_hud on|off|toggle|status — клиентский HUD"
						+ "\n/arena_hud mode external|minimal|full|off — режим интерфейса"
						+ "\n/arena_hud bossbar on|off|status — BossBar (по умолчанию off)"), false);
		return 1;
	}

	private static int setBossBar(CommandSourceStack source, boolean enabled) {
		INSTANCE.bossBarEnabled = enabled;
		MinecraftServer server = source.getServer();
		if (!enabled && server != null) {
			INSTANCE.hideAllBars();
			INSTANCE.removeAllViewers(server);
		}
		source.sendSuccess(() -> Component.literal(
				"BossBar HUD " + (enabled ? "включён (отладка)." : "выключен.")), false);
		return 1;
	}

	private static int showBossBarStatus(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"BossBar HUD: " + (INSTANCE.bossBarEnabled ? "включён" : "выключен (по умолчанию)")
						+ "\nВ обычной игре используйте клиентский round HUD."), false);
		return 1;
	}

	private static int showHudStatus(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean clientOn = ArenaRoundHudSync.isClientHudEnabled(player);
		source.sendSuccess(() -> Component.literal(
				"Client HUD: " + (clientOn ? "включён" : "выключен")
						+ "\nHUD mode: " + INSTANCE.hudMode
						+ "\nBossBar: " + (INSTANCE.bossBarEnabled ? "включён" : "выключен")), false);
		return 1;
	}

	private static int setHudMode(CommandSourceStack source, String raw) {
		ArenaHudDisplayMode parsed = ArenaHudDisplayMode.parse(raw, null);
		if (parsed == null) {
			source.sendFailure(Component.literal("Использование: /arena_hud mode external|minimal|full|off"));
			return 0;
		}
		INSTANCE.hudMode = parsed;
		source.sendSuccess(() -> Component.literal("HUD mode: " + parsed), false);
		return 1;
	}

	private static int showHudDebug(CommandSourceStack source) {
		String text = INSTANCE.buildDebugText(source.getServer())
				+ "\n\nClient HUD v2: на клиенте смотрите чат после /arena_hud_debug (client) "
				+ "или откройте round HUD во время боя.";
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static final class PendingHide {
		private int ticksRemaining;
		private final int generation;

		private PendingHide(int ticksRemaining, int generation) {
			this.ticksRemaining = ticksRemaining;
			this.generation = generation;
		}
	}
}
