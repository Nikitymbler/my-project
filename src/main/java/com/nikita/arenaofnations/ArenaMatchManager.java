package com.nikita.arenaofnations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class ArenaMatchManager {
	private static final ArenaMatchManager INSTANCE = new ArenaMatchManager();

	private ArenaMatchState state = ArenaMatchState.IDLE;
	private int remainingTicks;
	private int battleTicksElapsed;
	private boolean joinClosedAnnounced;
	private boolean battleOutcomeDecided;
	private Vec3 arenaCenter = Vec3.ZERO;
	private Country lastRoundWinner;
	private boolean lastRoundWasTie;
	private int lastRoundParticipantCount;
	private boolean economyTestBattle;
	private boolean testBattleDeferred;

	private final LinkedHashSet<Country> activeCountries = new LinkedHashSet<>();
	/** Countries that joined this round; never shrinks on elimination (used for score tiers). */
	private final LinkedHashSet<Country> roundParticipants = new LinkedHashSet<>();
	private final Map<Country, Queue<PendingFighter>> reserves = new EnumMap<>(Country.class);
	private final Queue<PendingFighter> nextRoundQueue = new ArrayDeque<>();
	private final Map<Country, Integer> giftCounts = new EnumMap<>(Country.class);
	private final Map<Country, Integer> spawnCounters = new EnumMap<>(Country.class);
	private final Map<Country, Double> damageDealt = new EnumMap<>(Country.class);
	private final Map<Country, Integer> countryBaseSlots = new EnumMap<>(Country.class);
	private final boolean[] occupiedBaseSlots = new boolean[ArenaCountryBaseLayout.BASE_SLOT_COUNT];
	private java.util.EnumMap<Country, Integer> cachedLivingCounts;
	private long cachedLivingCountsTick = -1L;

	private ArenaMatchManager() {
		for (Country country : Country.values()) {
			reserves.put(country, new ArrayDeque<>());
			giftCounts.put(country, 0);
			spawnCounters.put(country, 0);
			damageDealt.put(country, 0.0);
		}
	}

	public static ArenaMatchManager get() {
		return INSTANCE;
	}

	public static void register() {
		ArenaConfig.load();
		ArenaEntities.register();
		ArenaRoundHudSync.registerCommon();
		ArenaCoreManager.get().resetAllStates();
		ArenaCoreRescueManager.get().clearAll();
		ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.tick(server));
		ArenaDamageTracker.register();
		ArenaMatchCommands.register();
		ArenaBalanceCommands.register();
		ArenaMeleeCommands.register();
		ArenaTestScenarioCommands.register();
		ArenaLifecycleCommands.register();
		ArenaLayoutCommands.register();
		ArenaBuildManager.register();
		ArenaCoreCommands.register();
		ArenaHudManager.register();
		ArenaRoundHudSync.register();
		ArenaViewerEventManager.register();
		ArenaStreamToEarnCommands.register();
		ArenaStreamToEarnHttpBridge.register();
		ArenaOverlayStateService.register();
		ArenaOverlayCommands.register();
		ArenaCoreDisplayManager.register();
		ArenaBaseMarkerCommands.register();
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity.level().isClientSide() || !(entity instanceof ArenaFighterEntity)) {
				return;
			}
			INSTANCE.invalidateLivingCountsCache();
			if (!(entity.level() instanceof ServerLevel level)) {
				return;
			}
			for (Entity other : level.getAllEntities()) {
				if (!(other instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter)) {
					continue;
				}
				if (fighter.getTarget() == entity) {
					fighter.setTarget(null);
					fighter.setPersistentAngerTarget(null);
					fighter.getNavigation().stop();
				}
			}
		});
	}

	public ArenaMatchState getState() {
		return state;
	}

	public int getRemainingSeconds() {
		return Math.max(0, (remainingTicks + 19) / 20);
	}

	public int getRemainingStateTicks() {
		return Math.max(0, remainingTicks);
	}

	public Set<Country> getActiveCountries() {
		return Set.copyOf(activeCountries);
	}

	public Set<Country> getCurrentRoundCountries() {
		return Set.copyOf(roundParticipants);
	}

	public int getBaseSlot(Country country) {
		return countryBaseSlots.getOrDefault(country, -1);
	}

	public Map<Country, Integer> getCountryBaseSlots() {
		return Map.copyOf(countryBaseSlots);
	}

	public int getActiveCountryLimit() {
		return ArenaCountryBaseLayout.MAX_ACTIVE_COUNTRIES;
	}

	public int getRoundParticipantCount() {
		return roundParticipants.size();
	}

	public int getOriginalParticipantCount() {
		if (!roundParticipants.isEmpty()) {
			return roundParticipants.size();
		}
		return lastRoundParticipantCount;
	}

	public Country getLastRoundWinner() {
		return lastRoundWinner;
	}

	public boolean wasLastRoundTie() {
		return lastRoundWasTie;
	}

	public Vec3 getMatchCenter() {
		return arenaCenter;
	}

	public int getLiveFighterCount(MinecraftServer server, Country country) {
		ServerLevel level = ArenaSpawns.resolveFightLevel(server, server.overworld());
		return countLivingFighters(level, country);
	}

	public int getReserveCount(Country country) {
		return getReserveSize(country);
	}

	public java.util.EnumMap<Country, Integer> countLivingFightersByCountry(MinecraftServer server) {
		java.util.EnumMap<Country, Integer> counts = new java.util.EnumMap<>(Country.class);
		for (Country country : Country.values()) {
			counts.put(country, 0);
		}
		ServerLevel level = ArenaSpawns.resolveFightLevel(server, server.overworld());
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive() || !FighterFactory.isArenaFighter(living)) {
				continue;
			}
			Country country = FighterFactory.getCountry(living);
			if (country != null) {
				counts.merge(country, 1, Integer::sum);
			}
		}
		return counts;
	}

	public int getRemainingContenderCount() {
		int count = 0;
		for (Country country : activeCountries) {
			if (!ArenaCoreRescueManager.get().isEliminated(country)) {
				count++;
			}
		}
		return count;
	}

	public int getReserveSize(Country country) {
		return reserves.get(country).size();
	}

	public void clearCountryReserve(Country country) {
		reserves.get(country).clear();
	}

	public Iterable<PendingFighter> getReserveFighters(Country country) {
		return reserves.get(country);
	}

	public void setEconomyTestBattle(boolean enabled) {
		this.economyTestBattle = enabled;
	}

	public boolean isEconomyTestBattle() {
		return economyTestBattle;
	}

	/** Test-only: extend/replace remaining BATTLE timer. */
	public synchronized void setBattleRemainingSecondsForTest(int seconds) {
		if (state == ArenaMatchState.BATTLE) {
			remainingTicks = Math.max(1, seconds) * 20;
		}
	}

	/**
	 * Test-only: register participants and activate cores without starting BATTLE.
	 * Callers spawn fighters via {@link #spawnTestFightersOnField}, arrange them, then {@link #startPreparedTestBattle}.
	 */
	public synchronized void prepareTestBattle(
			MinecraftServer server,
			ServerLevel level,
			Vec3 origin,
			Country... participants) {
		if (participants.length == 0) {
			ArenaOfNations.LOGGER.error("prepareTestBattle called with no participants");
			return;
		}

		arenaCenter = ArenaSpawns.resolveMatchCenter(server, origin);
		activeCountries.clear();
		roundParticipants.clear();
		clearDamageStats();
		ArenaCoreRescueManager.get().clearAll();
		ArenaCoreManager.get().clearProtectionStateTracking();

		for (Country country : participants) {
			addParticipant(server, country);
		}

		testBattleDeferred = true;
		state = ArenaMatchState.IDLE;
		remainingTicks = 0;
		battleTicksElapsed = 0;
		joinClosedAnnounced = false;
		battleOutcomeDecided = false;
	}

	/** Test-only: start BATTLE after deferred test setup and fighter placement. */
	public synchronized void startPreparedTestBattle(MinecraftServer server) {
		if (!testBattleDeferred) {
			ArenaOfNations.LOGGER.warn("startPreparedTestBattle called without prepareTestBattle");
		}
		testBattleDeferred = false;
		startBattle(server);
		broadcast(server, Component.literal("Битва началась!"));
	}

	/**
	 * Test-only: register participants with active cores and start BATTLE immediately.
	 * Does not spawn fighters — callers add them via {@link #handleGift} separately.
	 */
	public synchronized void beginTestBattle(
			MinecraftServer server,
			ServerLevel level,
			Vec3 origin,
			Country... participants) {
		if (participants.length == 0) {
			ArenaOfNations.LOGGER.error("beginTestBattle called with no participants");
			return;
		}

		arenaCenter = ArenaSpawns.resolveMatchCenter(server, origin);
		activeCountries.clear();
		roundParticipants.clear();
		clearDamageStats();
		ArenaCoreRescueManager.get().clearAll();
		ArenaCoreManager.get().clearProtectionStateTracking();

		for (Country country : participants) {
			addParticipant(server, country);
		}

		startBattle(server);
		broadcast(server, Component.literal("Битва началась!"));
	}

	/**
	 * Test-only: spawn SCOUT fighters directly on the fight level, bypassing reserve queue.
	 * Used by placement/core scenarios that need fighters on the field before or during BATTLE.
	 */
	public synchronized void spawnTestFightersOnField(
			MinecraftServer server,
			ServerLevel level,
			Country country,
			int count) {
		if (!economyTestBattle) {
			ArenaOfNations.LOGGER.warn("spawnTestFightersOnField called outside economy test");
		}
		if (count < 1) {
			return;
		}
		if (!activeCountries.contains(country)) {
			addParticipant(server, country);
		}
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		for (int i = 0; i < count; i++) {
			spawnFighter(server, fightLevel, new PendingFighter(country, FighterTier.SCOUT, 1));
		}
	}

	public int getNextRoundQueueSize() {
		return nextRoundQueue.size();
	}

	public int getGiftCount(Country country) {
		return giftCounts.getOrDefault(country, 0);
	}

	public synchronized void addDamage(Country country, float amount) {
		if (amount <= 0.0F) {
			return;
		}
		damageDealt.merge(country, (double) amount, Double::sum);
	}

	public synchronized double getDamageDealt(Country country) {
		return damageDealt.getOrDefault(country, 0.0);
	}

	public int countLivingFighters(ServerLevel level, Country country) {
		if (level == null) {
			return 0;
		}
		long tick = level.getGameTime();
		if (cachedLivingCounts != null && cachedLivingCountsTick == tick) {
			return cachedLivingCounts.getOrDefault(country, 0);
		}
		refreshLivingCountsCache(level, tick);
		return cachedLivingCounts.getOrDefault(country, 0);
	}

	private void refreshLivingCountsCache(ServerLevel level, long tick) {
		java.util.EnumMap<Country, Integer> counts = new java.util.EnumMap<>(Country.class);
		for (Country country : Country.ALL) {
			counts.put(country, 0);
		}
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof LivingEntity living)
					|| !living.isAlive()
					|| !FighterFactory.isArenaFighter(living)) {
				continue;
			}
			Country fighterCountry = FighterFactory.getCountry(living);
			if (fighterCountry != null) {
				counts.merge(fighterCountry, 1, Integer::sum);
			}
		}
		cachedLivingCounts = counts;
		cachedLivingCountsTick = tick;
	}

	public int countLivingFightersUncached(ServerLevel level, Country country) {
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof LivingEntity living
					&& living.isAlive()
					&& FighterFactory.isArenaFighter(living)
					&& FighterFactory.getCountry(living) == country) {
				count++;
			}
		}
		return count;
	}

	public void invalidateLivingCountsCache() {
		cachedLivingCounts = null;
		cachedLivingCountsTick = -1L;
	}

	public int countLivingFighters(ServerLevel level) {
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof LivingEntity living && living.isAlive() && FighterFactory.isArenaFighter(living)) {
				count++;
			}
		}
		return count;
	}

	public synchronized void handleGift(MinecraftServer server, ServerLevel level, Vec3 origin, Country country, int coins) {
		if (coins < 1) {
			broadcast(server, Component.literal("Подарок слишком маленький. Минимум: 1 монета."));
			return;
		}

		ArenaSpawns.beginBatch();
		giftCounts.put(country, giftCounts.getOrDefault(country, 0) + 1);
		for (int i = 0; i < coins; i++) {
			PendingFighter pending = new PendingFighter(country, FighterTier.SCOUT, coins);

			if (!canAcceptGift(server, country, pending)) {
				continue;
			}

			switch (state) {
				case IDLE -> {
					if (testBattleDeferred && activeCountries.contains(country)) {
						acceptFighter(server, level, pending);
						continue;
					}
					startWaiting(server, level, origin, pending);
				}
				case WAITING_FOR_OPPONENT -> handleGiftWhileWaiting(server, level, pending);
				case BATTLE -> handleGiftWhileBattle(server, level, pending);
				case BREAK -> {
					nextRoundQueue.add(pending);
				}
			}
		}

		if (state == ArenaMatchState.BREAK) {
			broadcast(server, Component.literal(
					"Подарок для " + country.getDisplayName() + ": "
							+ coins
							+ " бойцов сохранены в очередь следующего раунда."));
		}
	}

	public synchronized void reset(MinecraftServer server) {
		ArenaTestScenarioCommands.cancelDeferred();
		clearAllFighters(server);
		activeCountries.clear();
		roundParticipants.clear();
		nextRoundQueue.clear();
		for (Country country : Country.values()) {
			reserves.get(country).clear();
			giftCounts.put(country, 0);
			spawnCounters.put(country, 0);
		}
		clearDamageStats();
		clearBaseLayout();
		ArenaCoreCombatManager.get().clearAll(server);
		ArenaCoreRescueManager.get().clearAll();
		ArenaCoreManager.get().resetRound(server);
		state = ArenaMatchState.IDLE;
		remainingTicks = 0;
		battleTicksElapsed = 0;
		joinClosedAnnounced = false;
		battleOutcomeDecided = false;
		lastRoundWinner = null;
		lastRoundWasTie = false;
		lastRoundParticipantCount = 0;
		economyTestBattle = false;
		testBattleDeferred = false;
		ArenaMeleeDiagnostics.reset();
		ArenaHudManager.get().clearAll(server);
		broadcast(server, Component.literal("Раунд арены сброшен. Состояние: IDLE"));
	}

	/**
	 * Called when a country's rescue timer expires (or equivalent final knockout).
	 * Always clears that country's fighters/reserve/combat links, even if the battle
	 * already ended — HUD elimination must not leave living fighters on the field.
	 */
	public synchronized void onCountryEliminated(MinecraftServer server, Country country) {
		ArenaTestScenarioCommands.notifyCountryEliminated(country);

		clearFightersForCountry(server, country);
		reserves.get(country).clear();
		activeCountries.remove(country);
		ArenaCoreCombatManager.get().onCountryEliminated(server, country);
		ArenaHudManager.get().onCountryEliminated(country);

		if (state != ArenaMatchState.BATTLE || battleOutcomeDecided) {
			return;
		}

		List<Country> remaining = new ArrayList<>();
		for (Country active : activeCountries) {
			if (!ArenaCoreRescueManager.get().isEliminated(active)) {
				remaining.add(active);
			}
		}

		if (remaining.size() > 1) {
			return;
		}

		battleOutcomeDecided = true;
		lastRoundParticipantCount = Math.max(lastRoundParticipantCount, roundParticipants.size());

		if (remaining.size() == 1) {
			Country winner = remaining.getFirst();
			lastRoundWinner = winner;
			lastRoundWasTie = false;
			broadcast(server, Component.literal(winner.getDisplayName() + " победила!"));
			ArenaScoreManager.awardBattleWin(server, winner, Math.max(2, roundParticipants.size()));
		} else {
			lastRoundWinner = null;
			lastRoundWasTie = true;
			broadcast(server, Component.literal("Ничья!"));
		}

		clearAllFighters(server);
		clearReserves();
		beginBreak(server);
	}

	private void startWaiting(MinecraftServer server, ServerLevel level, Vec3 origin, PendingFighter pending) {
		ArenaConfig config = ArenaConfig.get();
		arenaCenter = ArenaSpawns.resolveMatchCenter(server, origin);
		activeCountries.clear();
		roundParticipants.clear();
		clearBaseLayout();
		addParticipant(server, pending.getCountry());
		clearDamageStats();
		ArenaCoreRescueManager.get().clearAll();
		state = ArenaMatchState.WAITING_FOR_OPPONENT;
		remainingTicks = config.getWaitingSeconds() * 20;
		joinClosedAnnounced = false;
		battleTicksElapsed = 0;
		battleOutcomeDecided = false;

		acceptFighter(server, level, pending);
		broadcast(server, Component.literal(
				pending.getCountry().getDisplayName()
						+ " захватила арену! Ожидание соперника: "
						+ config.getWaitingSeconds()
						+ " секунд."));
	}

	private void handleGiftWhileWaiting(MinecraftServer server, ServerLevel level, PendingFighter pending) {
		Country firstCountry = activeCountries.iterator().next();

		if (pending.getCountry() == firstCountry) {
			acceptFighter(server, level, pending);
			return;
		}

		addParticipant(server, pending.getCountry());
		acceptFighter(server, level, pending);
		startBattle(server);
		broadcast(server, Component.literal(pending.getCountry().getDisplayName() + " вступает в бой!"));
		broadcast(server, Component.literal("Битва началась!"));
	}

	private void handleGiftWhileBattle(MinecraftServer server, ServerLevel level, PendingFighter pending) {
		Country country = pending.getCountry();

		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		if (core.getCurrentHealth() <= 0.0F || ArenaCoreRescueManager.get().isRescuing(country)) {
			ArenaCoreRescueManager.get().tryHealDestroyedCoreWithGift(server, country);
			acceptFighter(server, level, pending);
			return;
		}

		if (activeCountries.contains(country)) {
			acceptFighter(server, level, pending);
			return;
		}

		addParticipant(server, country);
		acceptFighter(server, level, pending);
		broadcast(server, Component.literal(country.getDisplayName() + " вступает в бой!"));
	}

	private boolean canAcceptGift(MinecraftServer server, Country country, PendingFighter pending) {
		if (ArenaCoreRescueManager.get().isEliminated(country)) {
			if (state == ArenaMatchState.BREAK) {
				return true;
			}
			nextRoundQueue.add(pending);
			broadcast(server, Component.literal(
					"Страна " + country.getDisplayName()
							+ " выбыла из раунда. Боец отправлен в очередь следующего раунда."));
			return false;
		}

		if (roundParticipants.contains(country) && !activeCountries.contains(country)) {
			nextRoundQueue.add(pending);
			broadcast(server, Component.literal(
					"Страна " + country.getDisplayName()
							+ " уже участвовала в этом раунде. Боец отправлен в очередь следующего раунда."));
			return false;
		}

		if (activeCountries.contains(country)) {
			return true;
		}

		if (state == ArenaMatchState.BATTLE) {
			ArenaConfig config = ArenaConfig.get();
			boolean joinOpen = remainingTicks > config.getJoinClosesBeforeEndSeconds() * 20;
			if (!joinOpen) {
				nextRoundQueue.add(pending);
				broadcast(server, Component.literal(
						"Вход закрыт. Боец " + country.getDisplayName()
								+ " отправлен в очередь следующего раунда."));
				return false;
			}
		}

		if (activeCountries.size() >= ArenaCountryBaseLayout.MAX_ACTIVE_COUNTRIES) {
			nextRoundQueue.add(pending);
			broadcast(server, Component.literal(
					"Достигнут лимит " + ArenaCountryBaseLayout.MAX_ACTIVE_COUNTRIES
							+ " стран в раунде. "
							+ country.getDisplayName()
							+ " не вступает в текущий бой — боец в очереди следующего раунда."));
			return false;
		}

		return true;
	}

	private void addParticipant(MinecraftServer server, Country country) {
		if (!activeCountries.add(country)) {
			return;
		}
		roundParticipants.add(country);
		assignBaseSlot(server, country);
		ArenaCoreManager.get().activate(server, country);
	}

	private void assignBaseSlot(MinecraftServer server, Country country) {
		if (countryBaseSlots.containsKey(country)) {
			return;
		}
		int slot = ArenaCountryBaseLayout.pickSlot(occupiedBaseSlots);
		if (slot < 0) {
			ArenaOfNations.LOGGER.error("No free base slot for country {}", country.getId());
			return;
		}
		occupiedBaseSlots[slot] = true;
		countryBaseSlots.put(country, slot);
	}

	private void clearBaseLayout() {
		countryBaseSlots.clear();
		Arrays.fill(occupiedBaseSlots, false);
	}

	/** Test-only: register round participants and base slots. */
	public synchronized void registerTestCountries(MinecraftServer server, Country... countries) {
		for (Country country : countries) {
			if (!roundParticipants.contains(country)) {
				addParticipant(server, country);
			}
		}
	}

	private void startBattle(MinecraftServer server) {
		state = ArenaMatchState.BATTLE;
		remainingTicks = resolveBattleDurationSeconds() * 20;
		battleTicksElapsed = 0;
		joinClosedAnnounced = false;
		battleOutcomeDecided = false;
	}

	private int resolveBattleDurationSeconds() {
		if (economyTestBattle) {
			return ArenaEconomyTest.BATTLE_SECONDS;
		}
		return ArenaConfig.get().getBattleSeconds();
	}

	private void acceptFighter(MinecraftServer server, ServerLevel level, PendingFighter pending) {
		// Unlimited live-field mode: gifts always enqueue to reserve.
		reserves.get(pending.getCountry()).add(pending);
	}

	private void spawnFighter(MinecraftServer server, ServerLevel level, PendingFighter pending) {
		int index = spawnCounters.get(pending.getCountry());
		spawnCounters.put(pending.getCountry(), index + 1);
		ArenaSpawns.Target target = ArenaSpawns.resolve(server, level, arenaCenter, pending.getCountry(), index);
		if (target == null) {
			ArenaOfNations.LOGGER.error("Failed to resolve spawn for {}", pending.getCountry().getId());
			return;
		}
		FighterFactory.create(target.level(), target.pos(), pending.getCountry(), pending.getTier());
	}

	private synchronized void tick(MinecraftServer server) {
		if (state == ArenaMatchState.IDLE) {
			return;
		}

		ServerLevel level = server.overworld();

		if (state == ArenaMatchState.BATTLE) {
			tickBattle(server, level);
			return;
		}

		if (state == ArenaMatchState.WAITING_FOR_OPPONENT) {
			tickWaiting(server, level);
			return;
		}

		if (state == ArenaMatchState.BREAK) {
			tickBreak(server, level);
		}
	}

	private void tickWaiting(MinecraftServer server, ServerLevel level) {
		remainingTicks--;
		if (remainingTicks > 0) {
			return;
		}

		Country holder = activeCountries.isEmpty() ? null : activeCountries.iterator().next();
		clearAllFighters(server);
		clearReserves();

		if (holder != null) {
			lastRoundWinner = holder;
			lastRoundWasTie = false;
			lastRoundParticipantCount = 1;
			broadcast(server, Component.literal(holder.getDisplayName() + " удержала арену без соперника!"));
			ArenaScoreManager.awardHold(server, holder);
		} else {
			lastRoundWinner = null;
			lastRoundWasTie = true;
			lastRoundParticipantCount = 0;
		}

		beginBreak(server);
	}

	private void tickBattle(MinecraftServer server, ServerLevel level) {
		if (battleOutcomeDecided) {
			return;
		}

		ArenaConfig config = ArenaConfig.get();
		battleTicksElapsed++;
		remainingTicks--;

		// Gift commands run before END_SERVER_TICK, so rescue gift wins over same-tick expiry.
		ArenaCoreRescueManager.get().tick(server);

		if (battleOutcomeDecided || state != ArenaMatchState.BATTLE) {
			return;
		}

		int joinCloseTicks = config.getJoinClosesBeforeEndSeconds() * 20;
		if (!joinClosedAnnounced && remainingTicks <= joinCloseTicks) {
			joinClosedAnnounced = true;
			broadcast(server, Component.literal("Вход новых стран закрыт!"));
		}

		if (config.getReserveWaveIntervalTicks() > 0
				&& battleTicksElapsed % config.getReserveWaveIntervalTicks() == 0) {
			releaseReserveWaves(level);
		}

		ArenaCoreManager.get().updateCoreProtectionStates(server);

		if (remainingTicks > 0) {
			return;
		}

		if (battleOutcomeDecided) {
			return;
		}

		battleOutcomeDecided = true;
		announceBattleResult(server, level);
		clearAllFighters(server);
		clearReserves();
		beginBreak(server);
	}

	private void tickBreak(MinecraftServer server, ServerLevel level) {
		remainingTicks--;
		if (remainingTicks > 0) {
			return;
		}

		activeCountries.clear();
		roundParticipants.clear();
		for (Country country : Country.values()) {
			spawnCounters.put(country, 0);
		}
		clearDamageStats();

		if (nextRoundQueue.isEmpty()) {
			state = ArenaMatchState.IDLE;
			remainingTicks = 0;
			lastRoundWinner = null;
			lastRoundWasTie = false;
			lastRoundParticipantCount = 0;
			ArenaCoreCombatManager.get().clearAll(server);
			ArenaCoreRescueManager.get().clearAll();
			ArenaCoreManager.get().resetRound(server);
			ArenaHudManager.get().clearAll(server);
			broadcast(server, Component.literal("Арена свободна. Ожидание первого подарка."));
			return;
		}

		Country firstCountry = nextRoundQueue.peek().getCountry();
		List<PendingFighter> starters = takeCountryFromNextRound(firstCountry);

		ArenaSpawns.beginBatch();
		Vec3 fallbackCenter = arenaCenter.equals(Vec3.ZERO)
				? Vec3.atBottomCenterOf(level.getSharedSpawnPos())
				: arenaCenter;
		arenaCenter = ArenaSpawns.resolveMatchCenter(server, fallbackCenter);

		ArenaCoreRescueManager.get().clearAll();
		addParticipant(server, firstCountry);
		ArenaCoreManager.get().activate(server, firstCountry);
		state = ArenaMatchState.WAITING_FOR_OPPONENT;
		remainingTicks = ArenaConfig.get().getWaitingSeconds() * 20;
		joinClosedAnnounced = false;
		battleTicksElapsed = 0;
		battleOutcomeDecided = false;

		for (PendingFighter pending : starters) {
			acceptFighter(server, level, pending);
		}

		broadcast(server, Component.literal(
				firstCountry.getDisplayName()
						+ " захватила арену! Ожидание соперника: "
						+ ArenaConfig.get().getWaitingSeconds()
						+ " секунд."));
	}

	private List<PendingFighter> takeCountryFromNextRound(Country country) {
		List<PendingFighter> taken = new ArrayList<>();
		List<PendingFighter> remaining = new ArrayList<>();

		while (!nextRoundQueue.isEmpty()) {
			PendingFighter pending = nextRoundQueue.poll();
			if (pending.getCountry() == country) {
				taken.add(pending);
			} else {
				remaining.add(pending);
			}
		}

		nextRoundQueue.addAll(remaining);
		return taken;
	}

	private void releaseReserveWaves(ServerLevel level) {
		ArenaConfig config = ArenaConfig.get();
		ArenaSpawns.beginBatch();
		MinecraftServer server = level.getServer();
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);

		for (Country country : activeCountries) {
			if (ArenaCoreRescueManager.get().isEliminated(country)) {
				continue;
			}
			Queue<PendingFighter> reserve = reserves.get(country);
			int waveCap = Math.max(0, config.getReserveWaveSize());
			int toRelease = Math.min(waveCap, reserve.size());
			int released = 0;

			while (released < toRelease && !reserve.isEmpty()) {
				PendingFighter pending = reserve.poll();
				spawnFighter(server, fightLevel, pending);
				released++;
			}
		}
	}

	private void announceBattleResult(MinecraftServer server, ServerLevel level) {
		Country bestCountry = null;
		double bestHpPercent = -1.0D;
		double bestCoreDamage = -1.0D;
		boolean tie = false;

		for (Country country : activeCountries) {
			if (ArenaCoreRescueManager.get().isEliminated(country)) {
				continue;
			}

			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			double hpPercent = core.getHealthPercent();
			double coreDamage = ArenaCoreManager.get().getCoreDamageDealt(country);

			if (bestCountry == null) {
				bestCountry = country;
				bestHpPercent = hpPercent;
				bestCoreDamage = coreDamage;
				tie = false;
				continue;
			}

			int hpCompare = compareWithEpsilon(hpPercent, bestHpPercent);
			if (hpCompare > 0) {
				bestCountry = country;
				bestHpPercent = hpPercent;
				bestCoreDamage = coreDamage;
				tie = false;
			} else if (hpCompare < 0) {
				continue;
			} else if (coreDamage > bestCoreDamage) {
				bestCountry = country;
				bestHpPercent = hpPercent;
				bestCoreDamage = coreDamage;
				tie = false;
			} else if (Math.abs(coreDamage - bestCoreDamage) <= ArenaEconomicArmyValue.VALUE_EPSILON) {
				tie = true;
			}
		}

		if (bestCountry == null || tie) {
			lastRoundWinner = null;
			lastRoundWasTie = true;
			lastRoundParticipantCount = Math.max(lastRoundParticipantCount, roundParticipants.size());
			broadcast(server, Component.literal("Ничья!"));
		} else {
			lastRoundWinner = bestCountry;
			lastRoundWasTie = false;
			lastRoundParticipantCount = Math.max(lastRoundParticipantCount, roundParticipants.size());
			broadcast(server, Component.literal(bestCountry.getDisplayName() + " победила!"));
			ArenaScoreManager.awardBattleWin(server, bestCountry, Math.max(2, roundParticipants.size()));
		}
	}

	private static int compareWithEpsilon(double left, double right) {
		if (Math.abs(left - right) <= ArenaEconomicArmyValue.VALUE_EPSILON) {
			return 0;
		}
		return Double.compare(left, right);
	}

	private void clearDamageStats() {
		for (Country country : Country.values()) {
			damageDealt.put(country, 0.0);
		}
	}

	private void beginBreak(MinecraftServer server) {
		ArenaConfig config = ArenaConfig.get();
		if (lastRoundParticipantCount <= 0) {
			lastRoundParticipantCount = roundParticipants.size();
		}
		state = ArenaMatchState.BREAK;
		remainingTicks = config.getBreakSeconds() * 20;
		activeCountries.clear();
		roundParticipants.clear();
		ArenaCoreCombatManager.get().clearAll(server);
		ArenaCoreRescueManager.get().clearAll();
		ArenaCoreManager.get().resetRound(server);
		ArenaHudManager.get().onEnterBreak();
		broadcast(server, Component.literal(
				"Новый раунд начнётся через " + config.getBreakSeconds() + " секунд."));
	}

	private void clearReserves() {
		for (Country country : Country.values()) {
			reserves.get(country).clear();
		}
	}

	private void clearAllFighters(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> toRemove = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (FighterFactory.isArenaFighter(entity)) {
					toRemove.add(entity);
				}
			}
			for (Entity entity : toRemove) {
				entity.discard();
			}
		}
	}

	private void clearFightersForCountry(MinecraftServer server, Country country) {
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> toRemove = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (FighterFactory.isArenaFighter(entity) && FighterFactory.getCountry(entity) == country) {
					toRemove.add(entity);
				}
			}
			for (Entity entity : toRemove) {
				entity.discard();
			}
		}
	}

	private static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	public String buildStatusText(ServerLevel level) {
		MinecraftServer server = level.getServer();
		StringBuilder builder = new StringBuilder();
		builder.append("Состояние: ").append(state).append('\n');
		builder.append("Оставшееся время: ").append(getRemainingSeconds()).append(" сек.\n");
		builder.append("Активных стран: ")
				.append(activeCountries.size())
				.append('/')
				.append(ArenaCountryBaseLayout.MAX_ACTIVE_COUNTRIES)
				.append('\n');
		builder.append("Живых претендентов: ").append(getRemainingContenderCount()).append('\n');
		builder.append("Выбыли: ");
		if (ArenaCoreRescueManager.get().getEliminatedCountries().isEmpty()) {
			builder.append("нет");
		} else {
			boolean first = true;
			for (Country country : ArenaCoreRescueManager.get().getEliminatedCountries()) {
				if (!first) {
					builder.append(", ");
				}
				builder.append(country.getCode());
				first = false;
			}
		}
		builder.append('\n');

		builder.append("Живые бойцы: ").append(countLivingFighters(level)).append('\n');
		builder.append("Очередь следующего раунда: ").append(getNextRoundQueueSize()).append('\n');

		LinkedHashSet<Country> listed = new LinkedHashSet<>(roundParticipants);
		listed.addAll(activeCountries);
		if (listed.isEmpty()) {
			builder.append("Статистика стран: нет");
		} else {
			builder.append("Страны раунда (код | бойцы | резерв | ядро% | защита | база):");
			BlockPos center = server != null
					? BlockPos.containing(arenaCenter.x, arenaCenter.y, arenaCenter.z)
					: BlockPos.ZERO;
			for (Country country : listed) {
				ArenaCoreState core = ArenaCoreManager.get().getState(country);
				int defenders = countLivingFighters(level, country);
				boolean protectedCore = ArenaCoreManager.get().isCoreProtected(level, country);
				int slot = getBaseSlot(country);
				builder.append('\n')
						.append(country.getCode())
						.append(" | б=")
						.append(defenders)
						.append(" | р=")
						.append(getReserveSize(country))
						.append(" | ядро=")
						.append(ArenaCoreManager.formatPercent(core.getHealthPercent()))
						.append('%')
						.append(" | ")
						.append(protectedCore ? "ЩИТ" : "УЯЗВ")
						.append(" | слот=")
						.append(slot >= 0 ? slot : "?");
				if (ArenaCoreRescueManager.get().isEliminated(country)) {
					builder.append(" | ВЫБЫЛА");
				} else if (ArenaCoreRescueManager.get().isRescuing(country)) {
					builder.append(" | СПАСЕНИЕ");
				}
				if (slot >= 0 && !center.equals(BlockPos.ZERO)) {
					BlockPos corePos = ArenaCountryBaseLayout.corePosition(center, slot);
					builder.append(" | core@")
							.append(corePos.getX())
							.append(',')
							.append(corePos.getZ());
				}
			}
		}

		return builder.toString();
	}

	public String buildDamageStatsText() {
		StringBuilder builder = new StringBuilder("Урон стран текущего раунда:");
		for (Country country : Country.values()) {
			builder.append('\n')
					.append("- ")
					.append(country.getDisplayName())
					.append(": бойцам=")
					.append(formatDamage(getDamageDealt(country)))
					.append(", вышкам=")
					.append(formatDamage(ArenaCoreManager.get().getCoreDamageDealt(country)));
		}
		return builder.toString();
	}

	private static String formatDamage(double damage) {
		return String.format(java.util.Locale.US, "%.1f", damage);
	}
}
