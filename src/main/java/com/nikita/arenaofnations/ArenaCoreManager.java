package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Session-memory manager for the four country cores of the current round.
 */
public final class ArenaCoreManager {
	private static final ArenaCoreManager INSTANCE = new ArenaCoreManager();

	private final Map<Country, ArenaCoreState> states = new EnumMap<>(Country.class);
	private final Map<Country, Double> coreDamageDealt = new EnumMap<>(Country.class);
	private final Map<Country, Boolean> lastCoreProtectionState = new EnumMap<>(Country.class);
	private boolean suppressProtectionAnnouncements;

	private ArenaCoreManager() {
		resetAllStates();
	}

	public static ArenaCoreManager get() {
		return INSTANCE;
	}

	public ArenaCoreState getState(Country country) {
		return states.get(country);
	}

	public List<ArenaCoreState> getAllStates() {
		return new ArrayList<>(states.values());
	}

	private static ServerLevel resolveFightLevel(ServerLevel level) {
		if (level == null || level.getServer() == null) {
			return level;
		}
		return ArenaSpawns.resolveFightLevel(level.getServer(), level);
	}

	/**
	 * True while the country has at least one living arena fighter on the fight level.
	 * Reserve count alone does not protect the core. Pure query — no side effects.
	 */
	public boolean isCoreProtected(ServerLevel level, Country country) {
		if (country == null) {
			return true;
		}
		ServerLevel fightLevel = resolveFightLevel(level);
		if (fightLevel == null) {
			return true;
		}
		return countActiveDefenders(fightLevel, country) > 0;
	}

	public int countActiveDefenders(ServerLevel level, Country country) {
		if (country == null) {
			return 0;
		}
		ServerLevel fightLevel = resolveFightLevel(level);
		if (fightLevel == null) {
			return 0;
		}
		return ArenaMatchManager.get().countLivingFighters(fightLevel, country);
	}

	/**
	 * Once per server tick during BATTLE — broadcasts only on real protection transitions.
	 */
	public void updateCoreProtectionStates(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			lastCoreProtectionState.clear();
			return;
		}

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, server.overworld());
		if (fightLevel == null) {
			return;
		}

		for (Country country : ArenaMatchManager.get().getActiveCountries()) {
			if (ArenaCoreRescueManager.get().isEliminated(country)) {
				lastCoreProtectionState.remove(country);
				continue;
			}

			boolean currentProtected = isCoreProtected(fightLevel, country);
			Boolean previous = lastCoreProtectionState.get(country);
			if (previous == null) {
				lastCoreProtectionState.put(country, currentProtected);
				continue;
			}
			if (previous == currentProtected) {
				continue;
			}

			lastCoreProtectionState.put(country, currentProtected);
			if (suppressProtectionAnnouncements) {
				continue;
			}
			if (currentProtected) {
				server.getPlayerList().broadcastSystemMessage(
						Component.literal("Главная вышка " + country.getDisplayName() + " снова защищена!"),
						false);
			} else {
				server.getPlayerList().broadcastSystemMessage(
						Component.literal("Главная вышка " + country.getDisplayName() + " стала уязвимой!"),
						false);
			}
		}
	}

	public void setProtectionAnnouncementsSuppressed(boolean suppressed) {
		this.suppressProtectionAnnouncements = suppressed;
	}

	public void activate(MinecraftServer server, Country country) {
		float maxHp = ArenaConfig.get().getCoreMaxHealth();
		ArenaCoreState state = states.get(country);
		state.activate(maxHp);
		refreshVisual(server, country);
	}

	public void deactivate(Country country) {
		states.get(country).deactivate();
	}

	/** Operator/debug damage — ignores defender protection. */
	public float damage(MinecraftServer server, Country country, float amount) {
		return applyDamage(server, country, amount, null);
	}

	/**
	 * Fighter core attack. Blocked while defenders are alive on the field.
	 *
	 * @return resulting core HP; unchanged when blocked
	 */
	public float damageFromFighter(
			MinecraftServer server,
			ServerLevel level,
			Country targetCoreCountry,
			Country attackerCountry,
			float amount) {
		if (amount <= 0.0F || isCoreProtected(level, targetCoreCountry)) {
			return states.get(targetCoreCountry).getCurrentHealth();
		}
		return applyDamage(server, targetCoreCountry, amount, attackerCountry);
	}

	private float applyDamage(MinecraftServer server, Country country, float amount, Country attackerCountry) {
		ArenaCoreState state = states.get(country);
		float before = state.getCurrentHealth();
		float health = state.damage(amount);
		// Do NOT rebuild fortress blocks on hit — only logical HP / overlay / markers update.
		// Physical visuals change only on activate / eliminate / rescue restore / full reset.

		float actual = Math.max(0.0F, before - health);
		if (actual > 0.0F && attackerCountry != null) {
			coreDamageDealt.merge(attackerCountry, (double) actual, Double::sum);
		}

		if (before > 0.0F && health <= 0.0F) {
			ArenaCoreRescueManager.get().onCoreDestroyed(server, country);
		}
		return health;
	}

	public float heal(MinecraftServer server, Country country, float amount) {
		ArenaCoreState state = states.get(country);
		// Logical heal only — no block rebuild mid-fight.
		return state.heal(amount);
	}

	public void restoreAfterRescue(MinecraftServer server, Country country, int percent) {
		ArenaCoreState state = states.get(country);
		state.restoreToPercent(percent);
		refreshVisual(server, country);
	}

	public void markEliminated(MinecraftServer server, Country country) {
		ArenaCoreState state = states.get(country);
		state.markEliminatedKeepDestroyed();
		refreshVisual(server, country);
	}

	public void resetRound(MinecraftServer server) {
		float maxHp = ArenaConfig.get().getCoreMaxHealth();
		for (Country country : Country.values()) {
			states.get(country).resetInactive(maxHp);
		}
		clearCoreDamageStats();
		lastCoreProtectionState.clear();

		ServerLevel level = ArenaBuildManager.resolveArenaLevel(server);
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (level != null && setup != null && setup.isConfigured() && setup.isBuilt()) {
			ArenaCoreBuilder.resetAllPhysicalBases(level, setup.getCenter());
		}
	}

	public void resetAllStates() {
		float maxHp = ArenaConfig.get().getCoreMaxHealth();
		for (Country country : Country.values()) {
			states.put(country, new ArenaCoreState(country, maxHp));
			coreDamageDealt.put(country, 0.0D);
		}
		lastCoreProtectionState.clear();
	}

	public void clearCoreDamageStats() {
		for (Country country : Country.values()) {
			coreDamageDealt.put(country, 0.0D);
		}
	}

	public void clearProtectionStateTracking() {
		lastCoreProtectionState.clear();
	}

	public double getCoreDamageDealt(Country country) {
		return coreDamageDealt.getOrDefault(country, 0.0D);
	}

	private void refreshVisual(MinecraftServer server, Country country) {
		if (server == null) {
			return;
		}
		ServerLevel level = ArenaBuildManager.resolveArenaLevel(server);
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (level == null || setup == null || !setup.isConfigured() || !setup.isBuilt()) {
			return;
		}
		ArenaCoreBuilder.applyVisual(level, setup.getCenter(), country, states.get(country).resolveVisual());
	}

	public static String formatHealth(float value) {
		return String.format(java.util.Locale.US, "%.1f", value);
	}

	public static String formatPercent(float percent) {
		return String.format(java.util.Locale.US, "%.1f", percent);
	}

	public String buildStatusText(MinecraftServer server, BlockPos arenaCenter) {
		ServerLevel level = server != null ? ArenaSpawns.resolveFightLevel(server, server.overworld()) : null;
		StringBuilder builder = new StringBuilder("Статус ядер:");
		for (Country country : Country.values()) {
			ArenaCoreState state = states.get(country);
			BlockPos pos = arenaCenter == null ? BlockPos.ZERO : ArenaPositions.getCorePosition(arenaCenter, country);
			int defenders = level == null ? 0 : countActiveDefenders(level, country);
			boolean protectedCore = level != null && isCoreProtected(level, country);
			builder.append('\n')
					.append(country.getDisplayName())
					.append(":\n")
					.append("Вышка: ")
					.append(formatHealth(state.getCurrentHealth()))
					.append('/')
					.append(formatHealth(state.getMaxHealth()))
					.append('\n')
					.append("Защитников на поле: ")
					.append(defenders)
					.append('\n')
					.append("Статус: ")
					.append(protectedCore ? "ЗАЩИЩЕНА" : "УЯЗВИМА");
			if (arenaCenter != null) {
				builder.append("\npos=")
						.append(pos.getX()).append(' ')
						.append(pos.getY()).append(' ')
						.append(pos.getZ());
			}
			builder.append('\n')
					.append(ArenaCoreRescueManager.get().formatCountryFlags(server, country));
		}
		return builder.toString();
	}

	public String buildCoreDamageStatsText(MinecraftServer server, ServerLevel level) {
		ServerLevel fightLevel = resolveFightLevel(level);
		StringBuilder builder = new StringBuilder("Статистика урона по вышкам:");
		for (Country country : Country.values()) {
			ArenaCoreState core = states.get(country);
			int defenders = countActiveDefenders(fightLevel, country);
			boolean protectedCore = isCoreProtected(fightLevel, country);
			int attackersTargeting = ArenaCoreCombatManager.get().countAttackersTargeting(country);
			builder.append('\n')
					.append(country.getDisplayName())
					.append(":\n")
					.append("Вышка: ")
					.append(formatHealth(core.getCurrentHealth()))
					.append('/')
					.append(formatHealth(core.getMaxHealth()))
					.append('\n')
					.append("Защитников на поле: ")
					.append(defenders)
					.append('\n')
					.append("Статус: ")
					.append(protectedCore ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
					.append('\n')
					.append("атакуют вышку: ")
					.append(attackersTargeting)
					.append('\n')
					.append("нанесено урона вышкам: ")
					.append(formatHealth((float) getCoreDamageDealt(country)));
		}
		return builder.toString();
	}
}
