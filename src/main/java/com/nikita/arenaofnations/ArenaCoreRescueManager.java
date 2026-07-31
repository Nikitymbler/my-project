package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Elimination countdown (variant C):
 * <ul>
 *   <li>Starts only when core HP ≤ 0 and living fighters on fight level = 0</li>
 *   <li>Resets if a fighter appears or core HP becomes &gt; 0</li>
 *   <li>Gift heals destroyed cores by {@code core_rescue_health_percent} and clears countdown</li>
 *   <li>Expiry with still HP ≤ 0 and 0 fighters → final elimination</li>
 * </ul>
 */
public final class ArenaCoreRescueManager {
	private static final ArenaCoreRescueManager INSTANCE = new ArenaCoreRescueManager();

	private int generation = 0;
	private final Map<Country, RescueState> states = new EnumMap<>(Country.class);

	private ArenaCoreRescueManager() {
		clearAll();
	}

	public static ArenaCoreRescueManager get() {
		return INSTANCE;
	}

	public int getGeneration() {
		return generation;
	}

	public void clearAll() {
		generation++;
		for (Country country : Country.values()) {
			states.put(country, new RescueState());
		}
	}

	/** True while the elimination countdown is running (0 fighters + destroyed core). */
	public boolean isRescuing(Country country) {
		RescueState state = states.get(country);
		return state != null && state.rescuing && !state.eliminated;
	}

	public boolean isEliminated(Country country) {
		RescueState state = states.get(country);
		return state != null && state.eliminated;
	}

	public int getRescueRemainingSeconds(MinecraftServer server, Country country) {
		RescueState state = states.get(country);
		if (state == null || !state.rescuing || state.eliminated || server == null) {
			return 0;
		}
		long now = server.overworld().getGameTime();
		long remainingTicks = Math.max(0L, state.endGameTime - now);
		return (int) ((remainingTicks + 19L) / 20L);
	}

	public long getRescueRemainingTicks(MinecraftServer server, Country country) {
		RescueState state = states.get(country);
		if (state == null || !state.rescuing || state.eliminated || server == null) {
			return 0L;
		}
		long now = server.overworld().getGameTime();
		return Math.max(0L, state.endGameTime - now);
	}

	public Set<Country> getRescuingCountries() {
		EnumSet<Country> result = EnumSet.noneOf(Country.class);
		for (Country country : Country.values()) {
			if (isRescuing(country)) {
				result.add(country);
			}
		}
		return result;
	}

	public Set<Country> getEliminatedCountries() {
		EnumSet<Country> result = EnumSet.noneOf(Country.class);
		for (Country country : Country.values()) {
			if (isEliminated(country)) {
				result.add(country);
			}
		}
		return result;
	}

	/**
	 * Core just hit 0 HP. Does not start countdown while defenders remain —
	 * {@link #tick} / this method only starts when fighters on fight level = 0.
	 */
	public boolean onCoreDestroyed(MinecraftServer server, Country country) {
		if (server == null || country == null) {
			return false;
		}
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return false;
		}
		if (!ArenaMatchManager.get().getActiveCountries().contains(country)) {
			return false;
		}

		RescueState state = states.get(country);
		if (state.eliminated) {
			return false;
		}

		broadcast(server, Component.literal(
				"Ядро " + country.getDisplayName() + " сбито!"));
		return tryStartCountdown(server, country);
	}

	/**
	 * Gift heal for a destroyed core (HP ≤ 0). Clears countdown if running.
	 * Works both during countdown and while defenders still hold the field.
	 */
	public boolean tryHealDestroyedCoreWithGift(MinecraftServer server, Country country) {
		RescueState state = states.get(country);
		if (state == null || state.eliminated || server == null || country == null) {
			return false;
		}

		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		if (core.getCurrentHealth() > 0.0F) {
			return false;
		}

		clearCountdown(state);

		int percent = ArenaConfig.get().getCoreRescueHealthPercent();
		ArenaCoreManager.get().restoreAfterRescue(server, country, percent);

		broadcast(server, Component.literal(
				country.getDisplayName()
						+ " восстановила ядро! +"
						+ percent
						+ "% прочности."));
		ArenaRoundHudSync.pushNow(server);
		return true;
	}

	/** @deprecated use {@link #tryHealDestroyedCoreWithGift} */
	@Deprecated
	public boolean tryRescueWithGift(MinecraftServer server, Country country) {
		return tryHealDestroyedCoreWithGift(server, country);
	}

	public void tick(MinecraftServer server) {
		if (server == null || ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return;
		}

		long now = server.overworld().getGameTime();
		List<Country> expired = new ArrayList<>();

		for (Country country : Country.values()) {
			RescueState state = states.get(country);
			if (state.eliminated) {
				continue;
			}
			if (!ArenaMatchManager.get().getActiveCountries().contains(country)) {
				if (state.rescuing) {
					clearCountdown(state);
				}
				continue;
			}
			if (state.startedGeneration != 0 && state.startedGeneration != generation && state.rescuing) {
				clearCountdown(state);
				continue;
			}

			boolean coreDown = isCoreDown(country);
			int living = ArenaMatchManager.get().getLiveFighterCount(server, country);

			if (state.rescuing) {
				if (!coreDown || living > 0) {
					clearCountdown(state);
					if (living > 0 && coreDown) {
						broadcast(server, Component.literal(
								"Таймер вылета снят: у " + country.getDisplayName()
										+ " снова есть бойцы на поле."));
					}
					ArenaRoundHudSync.pushNow(server);
					continue;
				}

				long remainingTicks = state.endGameTime - now;
				int remainingSeconds = (int) Math.max(0L, (remainingTicks + 19L) / 20L);
				announceCountdown(server, country, state, remainingSeconds);
				if (remainingTicks <= 0L) {
					expired.add(country);
				}
				continue;
			}

			if (coreDown && living == 0) {
				tryStartCountdown(server, country);
			}
		}

		// Mark every same-tick expiry eliminated before match callbacks, so multi-expiry
		// does not briefly treat another expiring country as the sole remaining winner.
		List<Country> toEliminate = new ArrayList<>();
		for (Country country : expired) {
			if (markRescueExpired(server, country)) {
				toEliminate.add(country);
			}
		}
		for (Country country : toEliminate) {
			ArenaMatchManager.get().onCountryEliminated(server, country);
			ArenaRoundHudSync.pushNowAfterElimination(server, country);
		}
	}

	private boolean tryStartCountdown(MinecraftServer server, Country country) {
		RescueState state = states.get(country);
		if (state == null || state.eliminated || state.rescuing) {
			return false;
		}
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			return false;
		}
		if (!ArenaMatchManager.get().getActiveCountries().contains(country)) {
			return false;
		}
		if (!isCoreDown(country)) {
			return false;
		}
		if (ArenaMatchManager.get().getLiveFighterCount(server, country) > 0) {
			return false;
		}

		int seconds = ArenaConfig.get().getCoreRescueSeconds();
		state.rescuing = true;
		state.eliminated = false;
		state.expireHandled = false;
		state.endGameTime = server.overworld().getGameTime() + (long) seconds * 20L;
		state.startedGeneration = generation;
		state.announced10 = false;
		state.announced5 = false;
		state.announced3 = false;
		state.announced2 = false;
		state.announced1 = false;

		broadcast(server, Component.literal(
				country.getDisplayName()
						+ ": ядро сбито и бойцов нет! Восстановление "
						+ seconds
						+ " сек. (подарок или боец снимают таймер)."));
		broadcastActionBar(server, Component.literal(
				"⏱ " + country.getDisplayName() + ": восстановление " + seconds + "с"));
		ArenaRoundHudSync.pushNow(server);
		return true;
	}

	private void clearCountdown(RescueState state) {
		state.rescuing = false;
		state.endGameTime = 0L;
		state.expireHandled = false;
		state.announced10 = false;
		state.announced5 = false;
		state.announced3 = false;
		state.announced2 = false;
		state.announced1 = false;
	}

	private static boolean isCoreDown(Country country) {
		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		return core.getCurrentHealth() <= 0.0F || core.isDestroyed();
	}

	private void announceCountdown(MinecraftServer server, Country country, RescueState state, int remainingSeconds) {
		if (remainingSeconds == 10 && !state.announced10) {
			state.announced10 = true;
			broadcast(server, Component.literal(
					country.getDisplayName() + ": на восстановление осталось 10 секунд!"));
		} else if (remainingSeconds == 5 && !state.announced5) {
			state.announced5 = true;
			broadcast(server, Component.literal(
					country.getDisplayName() + ": на восстановление осталось 5 секунд!"));
		} else if (remainingSeconds == 3 && !state.announced3) {
			state.announced3 = true;
			broadcast(server, Component.literal(
					country.getDisplayName() + ": на восстановление осталось 3 секунды!"));
		} else if (remainingSeconds == 2 && !state.announced2) {
			state.announced2 = true;
			broadcast(server, Component.literal(
					country.getDisplayName() + ": на восстановление осталось 2 секунды!"));
		} else if (remainingSeconds == 1 && !state.announced1) {
			state.announced1 = true;
			broadcast(server, Component.literal(
					country.getDisplayName() + ": на восстановление осталась 1 секунда!"));
		}
	}

	/**
	 * Marks a rescue expiry as final elimination without notifying the match yet.
	 *
	 * @return {@code true} if the country was marked eliminated and needs {@code onCountryEliminated}
	 */
	private boolean markRescueExpired(MinecraftServer server, Country country) {
		RescueState state = states.get(country);
		if (state == null || state.expireHandled || state.eliminated || !state.rescuing) {
			return false;
		}
		if (state.startedGeneration != generation) {
			clearCountdown(state);
			return false;
		}

		// Final gate: gift/fighter may have arrived same tick.
		if (!isCoreDown(country) || ArenaMatchManager.get().getLiveFighterCount(server, country) > 0) {
			clearCountdown(state);
			ArenaRoundHudSync.pushNow(server);
			return false;
		}

		state.expireHandled = true;
		state.rescuing = false;
		state.eliminated = true;
		state.endGameTime = 0L;

		ArenaCoreManager.get().markEliminated(server, country);

		Component eliminatedMessage = Component.literal(
				"✖ " + country.getDisplayName() + " выбыла из раунда!");
		broadcast(server, eliminatedMessage);
		broadcastActionBar(server, eliminatedMessage);
		return true;
	}

	/** Test / forced path: expire one country immediately. */
	private void expireRescue(MinecraftServer server, Country country) {
		if (markRescueExpired(server, country)) {
			ArenaMatchManager.get().onCountryEliminated(server, country);
			ArenaRoundHudSync.pushNowAfterElimination(server, country);
		}
	}

	public String buildRescueStatusText(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		StringBuilder builder = new StringBuilder();
		builder.append("Статус спасения ядер (вариант C):\n");
		builder.append("состояние матча=").append(match.getState()).append('\n');
		builder.append("countdown только при ядре≤0 и 0 бойцов на fight level\n");

		Set<Country> rescuing = getRescuingCountries();
		builder.append("страны на countdown: ");
		if (rescuing.isEmpty()) {
			builder.append("нет");
		} else {
			boolean first = true;
			for (Country country : rescuing) {
				if (!first) {
					builder.append(", ");
				}
				builder.append(country.getDisplayName())
						.append(" (")
						.append(getRescueRemainingSeconds(server, country))
						.append(" сек)");
				first = false;
			}
		}
		builder.append('\n');

		Set<Country> eliminated = getEliminatedCountries();
		builder.append("исключённые страны: ");
		if (eliminated.isEmpty()) {
			builder.append("нет");
		} else {
			boolean first = true;
			for (Country country : eliminated) {
				if (!first) {
					builder.append(", ");
				}
				builder.append(country.getDisplayName());
				first = false;
			}
		}
		builder.append('\n');

		int remaining = 0;
		for (Country country : match.getActiveCountries()) {
			if (!isEliminated(country)) {
				remaining++;
			}
		}
		builder.append("оставшиеся участники боя=").append(remaining);
		return builder.toString();
	}

	public String formatCountryFlags(MinecraftServer server, Country country) {
		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		boolean rescue = isRescuing(country);
		int seconds = getRescueRemainingSeconds(server, country);
		int living = server == null ? 0 : ArenaMatchManager.get().getLiveFighterCount(server, country);
		return "active=" + core.isActive()
				+ ", destroyed=" + core.isDestroyed()
				+ ", eliminated=" + isEliminated(country)
				+ ", countdown=" + rescue
				+ (rescue ? " (" + seconds + " сек)" : "")
				+ ", fighters=" + living
				+ ", hp=" + ArenaCoreManager.formatHealth(core.getCurrentHealth())
				+ '/' + ArenaCoreManager.formatHealth(core.getMaxHealth());
	}

	private static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	private static void broadcastActionBar(MinecraftServer server, Component message) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.displayClientMessage(message, true);
		}
	}

	/**
	 * Test scenarios only — force rescue countdown for HUD/visual checks.
	 */
	public void forceTestRescue(MinecraftServer server, Country country) {
		if (server == null || country == null) {
			return;
		}
		RescueState state = states.get(country);
		if (state == null) {
			return;
		}
		clearCountdown(state);
		state.eliminated = false;
		state.expireHandled = false;
		int seconds = ArenaConfig.get().getCoreRescueSeconds();
		state.rescuing = true;
		state.endGameTime = server.overworld().getGameTime() + (long) seconds * 20L;
		state.startedGeneration = generation;
		ArenaRoundHudSync.pushNow(server);
	}

	/**
	 * Test scenarios only — immediate elimination for HUD/visual checks.
	 */
	public void forceTestElimination(MinecraftServer server, Country country) {
		if (server == null || country == null) {
			return;
		}
		RescueState state = states.get(country);
		clearCountdown(state);
		state.eliminated = true;
		state.expireHandled = true;
		ArenaCoreManager.get().markEliminated(server, country);
		ArenaMatchManager.get().onCountryEliminated(server, country);
		ArenaRoundHudSync.pushNowAfterElimination(server, country);
	}

	private static final class RescueState {
		private boolean rescuing;
		private long endGameTime;
		private boolean eliminated;
		private boolean expireHandled;
		private int startedGeneration;
		private boolean announced10;
		private boolean announced5;
		private boolean announced3;
		private boolean announced2;
		private boolean announced1;
	}
}
