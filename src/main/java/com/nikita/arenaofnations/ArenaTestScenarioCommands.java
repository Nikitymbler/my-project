package com.nikita.arenaofnations;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Administrative automated test scenarios. Not part of live gameplay.
 */
final class ArenaTestScenarioCommands {
	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

	private static final List<String> SCENARIO_IDS = List.of(
			"reset",
			"duel",
			"mass_battle",
			"core_attack",
			"core_protection",
			"core_unprotected_attack",
			"melee_contact",
			"melee_density",
			"reserve",
			"core_rescue",
			"core_elimination",
			"hud_demo",
			"viewer_flow",
			"viewer_duplicate",
			"s2e_bridge",
			"s2e_local_gift",
			"twenty_countries",
			"twenty_countries_mass",
			"countries_joining",
			"hud_twenty_states",
			"layout_spawn_test",
			"core_after_last_defender",
			"overlay_twenty",
			"base_display_test",
			"base_marker_flags_test",
			"overlay_tiktok_test",
			"core_structure_integrity",
			"full_country_lifecycle",
			"mass_duel_reserve",
			"fighter_flags_test");

	private static DeferredScenario deferred;

	private ArenaTestScenarioCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(ArenaTestScenarioCommands::tickDeferred);
	}

	static void cancelDeferred() {
		boolean hadDeferred = deferred != null;
		deferred = null;
		if (ArenaFullCountryLifecycleTest.get().isRunning()) {
			ArenaFullCountryLifecycleTest.get().cancel();
			hadDeferred = true;
		}
		if (ArenaMassDuelReserveTest.get().isRunning()) {
			ArenaMassDuelReserveTest.get().cancel();
			hadDeferred = true;
		}
		if (ArenaS2eLocalGiftTest.get().isRunning()) {
			ArenaS2eLocalGiftTest.get().cancel();
			hadDeferred = true;
		}
		if (hadDeferred) {
			RUNNING.set(false);
		}
	}

	/** Called when {@link ArenaFullCountryLifecycleTest} finishes PASS/FAIL. */
	static void onLifecycleFinished() {
		RUNNING.set(false);
	}

	/**
	 * Snapshot elimination before BREAK clears rescue state.
	 */
	static void notifyCountryEliminated(Country country) {
		ArenaFullCountryLifecycleTest.get().onCountryEliminated(country);

		DeferredScenario scenario = deferred;
		if (scenario == null || scenario.phase != DeferredPhase.ELIMINATION_WAIT) {
			return;
		}
		if (country == Country.RU) {
			scenario.sawRuEliminated = true;
		}
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena_test_scenario")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("scenario", StringArgumentType.word())
						.suggests(ArenaTestScenarioCommands::suggestScenarios)
						.executes(ArenaTestScenarioCommands::runScenario))
				.executes(context -> {
					context.getSource().sendSuccess(() -> Component.literal(
							"Использование: /arena_test_scenario <scenario>\n"
									+ "Доступно: " + String.join(", ", SCENARIO_IDS)), false);
					return Command.SINGLE_SUCCESS;
				}));
	}

	private static int runScenario(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String raw = StringArgumentType.getString(context, "scenario").toLowerCase(Locale.ROOT);

		if (!SCENARIO_IDS.contains(raw)) {
			source.sendSuccess(() -> Component.literal(
					"Неизвестный сценарий. Доступно: " + String.join(", ", SCENARIO_IDS)), false);
			return Command.SINGLE_SUCCESS;
		}

		if (!RUNNING.compareAndSet(false, true)) {
			source.sendFailure(Component.literal("Уже выполняется другой тестовый сценарий. Подождите."));
			return 0;
		}

		try {
			ServerPlayer player = source.getPlayerOrException();
			MinecraftServer server = source.getServer();
			ServerLevel level = player.serverLevel();
			Vec3 origin = player.position();

			if ("core_rescue".equals(raw)) {
				startCoreRescue(server, level, origin, player.getUUID());
				source.sendSuccess(() -> Component.literal(
						"Сценарий: core_rescue\n"
								+ "Ядро 0 при живых → без countdown; discard RU → countdown; "
								+ "через 5с gift → heal ~50%."), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("core_elimination".equals(raw)) {
				startCoreElimination(server, level, origin, player.getUUID());
				int seconds = ArenaConfig.get().getCoreRescueSeconds();
				source.sendSuccess(() -> Component.literal(
						"Сценарий: core_elimination\n"
								+ "Ядро 0 + 0 бойцов → countdown "
								+ seconds + "с → ВЫБЫЛА."), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("viewer_flow".equals(raw)) {
				startViewerFlow(server, level, player.getUUID());
				source.sendSuccess(() -> Component.literal(
						"Сценарий: viewer_flow\nЗапущен автотест очереди зрительских событий..."), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("viewer_duplicate".equals(raw)) {
				startViewerDuplicate(server, level, player.getUUID());
				source.sendSuccess(() -> Component.literal(
						"Сценарий: viewer_duplicate\nЗапущен автотест дедупликации подарков..."), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("s2e_bridge".equals(raw)) {
				String earlyFailure = startS2eBridge(server, level, player.getUUID());
				if (earlyFailure != null) {
					RUNNING.set(false);
					source.sendSuccess(() -> Component.literal("Сценарий: s2e_bridge\n" + earlyFailure), false);
					return Command.SINGLE_SUCCESS;
				}
				source.sendSuccess(() -> Component.literal(
						"Сценарий: s2e_bridge\nЗапущен автотест командного моста StreamToEarn..."), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("s2e_local_gift".equals(raw)) {
				String started = ArenaS2eLocalGiftTest.get().start(server, level, player.getUUID());
				source.sendSuccess(() -> Component.literal("Сценарий: s2e_local_gift\n" + started), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("full_country_lifecycle".equals(raw)) {
				String started = ArenaFullCountryLifecycleTest.get().start(server, level, origin, player.getUUID());
				source.sendSuccess(() -> Component.literal("Сценарий: full_country_lifecycle\n" + started), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("mass_duel_reserve".equals(raw)) {
				String started = ArenaMassDuelReserveTest.get().start(server, level, origin, player.getUUID());
				source.sendSuccess(() -> Component.literal("Сценарий: mass_duel_reserve\n" + started), false);
				return Command.SINGLE_SUCCESS;
			}

			if ("core_after_last_defender".equals(raw)) {
				startCoreAfterLastDefender(server, level, origin, player.getUUID());
				source.sendSuccess(() -> Component.literal(
						"Сценарий: core_after_last_defender\n"
								+ "RU vs UA, UA с низким HP; через ~5с проверка атаки ядра."), false);
				return Command.SINGLE_SUCCESS;
			}

			String summary = switch (raw) {
				case "reset" -> runReset(server);
				case "duel" -> runDuel(server, level, origin);
				case "mass_battle" -> runMassBattle(server, level, origin);
				case "core_attack" -> runCoreAttack(server, level, origin);
				case "core_protection" -> runCoreProtection(server, level, origin);
				case "core_unprotected_attack" -> runCoreUnprotectedAttack(server, level, origin);
				case "melee_contact" -> runMeleeContact(server, level, origin);
				case "melee_density" -> runMeleeDensity(server, level, origin);
				case "reserve" -> runReserve(server, level, origin);
				case "hud_demo" -> runHudDemo(server, level, origin);
				case "twenty_countries" -> runTwentyCountries(server, level, origin);
				case "twenty_countries_mass" -> runTwentyCountriesMass(server, level, origin);
				case "countries_joining" -> runCountriesJoining(server, level, origin);
				case "hud_twenty_states" -> runHudTwentyStates(server, level, origin);
				case "layout_spawn_test" -> runLayoutSpawnTest(server, level, origin);
				case "overlay_twenty" -> runOverlayTwenty(server, level, origin);
				case "base_display_test" -> runBaseDisplayTest(server, level, origin);
				case "base_marker_flags_test" -> runBaseMarkerFlagsTest(server, level, origin);
				case "overlay_tiktok_test" -> runOverlayTikTokTest(server, level, origin);
				case "core_structure_integrity" -> runCoreStructureIntegrity(server, level, origin);
				case "fighter_flags_test" -> runFighterFlagsTest(server, level, origin);
				default -> "Неизвестный сценарий.";
			};

			source.sendSuccess(() -> Component.literal("Сценарий: " + raw + "\n" + summary), false);
			RUNNING.set(false);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			RUNNING.set(false);
			deferred = null;
			source.sendFailure(Component.literal("Сценарий прерван: " + e.getMessage()));
			ArenaOfNations.LOGGER.error("Test scenario '{}' failed", raw, e);
			return 0;
		}
	}

	private static String runReset(MinecraftServer server) {
		ArenaMatchManager.get().reset(server);
		return "Чистое тестовое состояние: бойцы, раунд, ядра, бой ядер и спасение сброшены. Очки и арена сохранены.";
	}

	private static String runDuel(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager.get().reset(server);
		gift(server, level, origin, Country.RU, FighterTier.SCOUT);
		gift(server, level, origin, Country.UA, FighterTier.SCOUT);
		return "Дуэль: RU Боец vs UA Боец. Битва запущена через систему подарков.";
	}

	private static String runMassBattle(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager.get().reset(server);
		giftMany(server, level, origin, Country.RU, FighterTier.SCOUT, 10);
		giftMany(server, level, origin, Country.UA, FighterTier.SCOUT, 10);
		giftMany(server, level, origin, Country.KZ, FighterTier.SCOUT, 5);
		giftMany(server, level, origin, Country.BY, FighterTier.SCOUT, 5);
		return "Массовая битва: RU 10, UA 10, KZ 5, BY 5 бойцов одного класса. Резерв и волны через раундовый движок.";
	}

	private static void beginEconomyTest(MinecraftServer server) {
		ArenaMatchManager match = ArenaMatchManager.get();
		match.reset(server);
		match.setEconomyTestBattle(true);
	}

	private static String buildEconomySummary(
			ServerLevel level,
			Country premiumCountry,
			FighterTier premiumTier,
			int premiumCount,
			Country swarmCountry,
			FighterTier swarmTier,
			int swarmCount,
			String expectation) {
		ArenaMatchManager match = ArenaMatchManager.get();
		int premiumCost = premiumCount * premiumTier.getGiftCost();
		int swarmCost = swarmCount * swarmTier.getGiftCost();
		int premiumLive = match.countLivingFighters(level, premiumCountry);
		int swarmLive = match.countLivingFighters(level, swarmCountry);
		float premiumHp = sumLivingHp(level, premiumCountry);
		float swarmHp = sumLivingHp(level, swarmCountry);

		return "Economy test\n"
				+ "Экономический тест: длительность боя "
				+ ArenaEconomyTest.BATTLE_SECONDS
				+ " секунд.\n"
				+ premiumCountry.getDisplayName()
				+ " "
				+ premiumTier.getDisplayName()
				+ " x"
				+ premiumCount
				+ " (gift cost "
				+ premiumCost
				+ ")\n"
				+ swarmCountry.getDisplayName()
				+ " "
				+ swarmTier.getDisplayName()
				+ " x"
				+ swarmCount
				+ " (gift cost "
				+ swarmCost
				+ ")\n"
				+ "живые "
				+ premiumCountry.getId().toUpperCase(java.util.Locale.ROOT)
				+ "="
				+ premiumLive
				+ ", резерв="
				+ match.getReserveSize(premiumCountry)
				+ ", HP="
				+ formatHp(premiumHp)
				+ '\n'
				+ "живые "
				+ swarmCountry.getId().toUpperCase(java.util.Locale.ROOT)
				+ "="
				+ swarmLive
				+ ", резерв="
				+ match.getReserveSize(swarmCountry)
				+ ", HP="
				+ formatHp(swarmHp)
				+ '\n'
				+ "состояние раунда: "
				+ match.getState()
				+ ", таймер="
				+ match.getRemainingSeconds()
				+ "с\n"
				+ "gift value RU="
				+ formatEconomyValue(ArenaEconomicArmyValue.compute(level, premiumCountry, match).totalGiftValue())
				+ ", UA="
				+ formatEconomyValue(ArenaEconomicArmyValue.compute(level, swarmCountry, match).totalGiftValue())
				+ '\n'
				+ "диагностика: /arena_damage_stats, /arena_economy_status\n"
				+ expectation;
	}

	private static String formatEconomyValue(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	private static float sumLivingHp(ServerLevel level, Country country) {
		float total = 0.0F;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| fighter.getArenaCountry() != country) {
				continue;
			}
			total += fighter.getHealth();
		}
		return total;
	}

	private static String formatHp(float hp) {
		return String.format(java.util.Locale.ROOT, "%.1f", hp);
	}

	private static String runCoreAttack(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager.get().reset(server);
		giftMany(server, level, origin, Country.RU, FighterTier.SCOUT, 5);
		gift(server, level, origin, Country.UA, FighterTier.SCOUT);
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		return "Атака ядра: RU 5 Бойцов vs UA 1 Боец. Украинское ядро active="
				+ uaCore.isActive()
				+ ", HP "
				+ ArenaCoreManager.formatHealth(uaCore.getCurrentHealth())
				+ "/"
				+ ArenaCoreManager.formatHealth(uaCore.getMaxHealth())
				+ ". После победы над бойцом атакующие должны перейти на ядро.";
	}

	private static String runCoreProtection(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.beginTestBattle(server, level, origin, Country.RU, Country.UA);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		match.spawnTestFightersOnField(server, level, Country.UA, 3);
		match.spawnTestFightersOnField(server, level, Country.RU, 1);
		giftMany(server, level, origin, Country.UA, FighterTier.SCOUT, 5);

		int uaLive = match.countLivingFighters(fightLevel, Country.UA);
		int uaReserve = match.getReserveSize(Country.UA);
		boolean uaProtected = ArenaCoreManager.get().isCoreProtected(fightLevel, Country.UA);
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);

		return "Core protection test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "RU: 1 Боец vs UA: 3 Бойца на поле (SCOUT) + 5 в резерве для волнового восстановления защиты.\n"
				+ "BATTLE сразу. Пока жив хотя бы один UA боец, RU не должен наносить урон вышке UA.\n"
				+ "После смерти последнего защитника вышка UA становится уязвимой.\n"
				+ "Когда из резерва выйдет новый UA боец, защита возвращается и удар по вышке отменяется.\n"
				+ "UA на поле: " + uaLive + ", резерв: " + uaReserve
				+ ", вышка: "
				+ ArenaCoreManager.formatHealth(uaCore.getCurrentHealth())
				+ "/"
				+ ArenaCoreManager.formatHealth(uaCore.getMaxHealth())
				+ ", статус: "
				+ (uaProtected ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
				+ ".\n"
				+ "Используйте /arena_status и /arena_core_damage_stats для контроля.";
	}

	private static String runCoreUnprotectedAttack(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.beginTestBattle(server, level, origin, Country.RU, Country.UA);
		match.spawnTestFightersOnField(server, level, Country.RU, 3);

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		String validation = validateCoreUnprotectedAttack(server, fightLevel, match);
		ArenaRoundHudSync.pushNow(server);

		boolean ruProtected = ArenaCoreManager.get().isCoreProtected(fightLevel, Country.RU);
		boolean uaProtected = ArenaCoreManager.get().isCoreProtected(fightLevel, Country.UA);
		int ruLive = match.countLivingFighters(fightLevel, Country.RU);
		int uaLive = match.countLivingFighters(fightLevel, Country.UA);

		return "Core unprotected attack test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "RU и UA зарегистрированы, BATTLE запущен без UA-бойцов.\n"
				+ "RU: " + ruLive + " бойцов, UA: " + uaLive + " бойцов.\n"
				+ "RU вышка: " + (ruProtected ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
				+ ", UA вышка: " + (uaProtected ? "ЗАЩИЩЕНА" : "УЯЗВИМА")
				+ ".\n"
				+ "Состояние: " + match.getState() + ", таймер: " + match.getRemainingSeconds() + "с.\n"
				+ "Ожидание: RU бойцы идут к UA вышке и наносят урон.\n"
				+ "Проверка: /arena_core_combat_status, /arena_core_damage_stats.\n"
				+ "После теста: /arena_spawn ua — одно сообщение о защите UA."
				+ validation;
	}

	private static String validateCoreUnprotectedAttack(
			MinecraftServer server,
			ServerLevel fightLevel,
			ArenaMatchManager match) {
		java.util.List<String> errors = new java.util.ArrayList<>();

		if (match.getState() != ArenaMatchState.BATTLE) {
			errors.add("state=" + match.getState() + " (ожидалось BATTLE)");
		}
		if (!match.getActiveCountries().contains(Country.RU)) {
			errors.add("RU не в activeCountries");
		}
		if (!match.getActiveCountries().contains(Country.UA)) {
			errors.add("UA не в activeCountries");
		}

		ArenaCoreState ruCore = ArenaCoreManager.get().getState(Country.RU);
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		if (!ruCore.isActive()) {
			errors.add("core RU не активна");
		}
		if (!uaCore.isActive()) {
			errors.add("core UA не активна");
		}

		int ruLive = match.countLivingFighters(fightLevel, Country.RU);
		int uaLive = match.countLivingFighters(fightLevel, Country.UA);
		if (ruLive != 3) {
			errors.add("RU living fighters=" + ruLive + " (ожидалось 3)");
		}
		if (uaLive != 0) {
			errors.add("UA living fighters=" + uaLive + " (ожидалось 0)");
		}

		if (!ArenaCoreManager.get().isCoreProtected(fightLevel, Country.RU)) {
			errors.add("isCoreProtected(RU)=false (ожидалось true)");
		}
		if (ArenaCoreManager.get().isCoreProtected(fightLevel, Country.UA)) {
			errors.add("isCoreProtected(UA)=true (ожидалось false)");
		}

		if (match.getRemainingSeconds() != ArenaEconomyTest.BATTLE_SECONDS) {
			errors.add("таймер=" + match.getRemainingSeconds() + "с (ожидалось "
					+ ArenaEconomyTest.BATTLE_SECONDS + "с)");
		}

		if (errors.isEmpty()) {
			return "\nVALIDATION OK.";
		}

		String message = String.join("; ", errors);
		ArenaOfNations.LOGGER.error("core_unprotected_attack validation failed: {}", message);
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[core_unprotected_attack] VALIDATION FAILED: " + message),
				false);
		return "\nVALIDATION FAILED: " + message;
	}

	private static String runMeleeContact(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginMeleeTestSetup(server, level, origin);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.spawnTestFightersOnField(server, level, Country.RU, 5);
		match.spawnTestFightersOnField(server, level, Country.UA, 5);

		String placementError = ArenaTestMeleePlacement.placeMeleeContact(server, fightLevel, origin, 5);
		if (placementError != null) {
			return "VALIDATION FAILED: " + placementError;
		}

		ArenaMeleeDiagnostics.reset();
		ArenaMatchManager.get().startPreparedTestBattle(server);

		String battleError = ArenaTestMeleePlacement.validateAfterBattleStart(server, fightLevel, 5, 5, true);
		if (battleError != null) {
			return "VALIDATION FAILED: " + battleError;
		}

		ArenaRoundHudSync.pushNow(server);

		return "Melee contact test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "RU 5 vs UA 5 Бойцов (SCOUT), две линии по X=±4 от центра, шаг Z=2.0 (~8 блоков между линиями).\n"
				+ "Проверка melee: используйте /arena_melee_status через 10 и 30 секунд.";
	}

	private static String runMeleeDensity(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginMeleeTestSetup(server, level, origin);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.spawnTestFightersOnField(server, level, Country.RU, 20);
		match.spawnTestFightersOnField(server, level, Country.UA, 20);

		String placementError = ArenaTestMeleePlacement.placeMeleeDensity(server, fightLevel, origin, 20);
		if (placementError != null) {
			return "VALIDATION FAILED: " + placementError;
		}

		ArenaMeleeDiagnostics.reset();
		ArenaMatchManager.get().startPreparedTestBattle(server);

		String battleError = ArenaTestMeleePlacement.validateAfterBattleStart(server, fightLevel, 20, 20, false);
		if (battleError != null) {
			return "VALIDATION FAILED: " + battleError;
		}

		ArenaRoundHudSync.pushNow(server);

		return "Melee density test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "RU 20 vs UA 20 Бойцов (SCOUT), зоны X=±6..±10, 4 ряда × 5 колонок, шаг ~2.0.\n"
				+ "Проверка melee: используйте /arena_melee_status через 10 и 30 секунд.";
	}

	private static void beginMeleeTestSetup(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager.get().prepareTestBattle(server, level, origin, Country.RU, Country.UA);
	}

	private static String runReserve(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager.get().reset(server);
		giftMany(server, level, origin, Country.RU, FighterTier.SCOUT, 12);
		gift(server, level, origin, Country.UA, FighterTier.SCOUT);
		int reserve = ArenaMatchManager.get().getReserveSize(Country.RU);
		return "Резерв: RU 12 бойцов (все в резерве до BATTLE, волнами по reserve_wave_size), UA 1 боец. "
				+ "RU резерв="
				+ reserve
				+ ". Волны через существующую систему.";
	}

	private static String runTwentyCountries(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.registerTestCountries(server, Country.ALL.toArray(Country[]::new));
		for (Country country : Country.ALL) {
			match.spawnTestFightersOnField(server, level, country, 1);
			match.clearCountryReserve(country);
		}
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		String validation = validateTwentyCountries(server, fightLevel, match, 1, 0);
		ArenaRoundHudSync.pushNow(server);
		return "Twenty countries test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "20 стран, по 1 бойцу, 20 вышек, BATTLE 180с.\n"
				+ validation;
	}

	private static String runTwentyCountriesMass(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.registerTestCountries(server, Country.ALL.toArray(Country[]::new));
		for (Country country : Country.ALL) {
			match.spawnTestFightersOnField(server, level, country, 5);
			match.clearCountryReserve(country);
		}
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		String validation = validateTwentyCountries(server, fightLevel, match, 5, 0);
		ArenaRoundHudSync.pushNow(server);
		return "Twenty countries mass test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "20×5=100 бойцов на поле, резерв 0, BATTLE 180с (ручной perf-тест).\n"
				+ validation;
	}

	private static String runHudTwentyStates(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.registerTestCountries(server, Country.ALL.toArray(Country[]::new));
		for (Country country : Country.ALL) {
			match.spawnTestFightersOnField(server, level, country, 1);
		}
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);

		setCoreHp(server, Country.RU, 180.0F);
		setCoreHp(server, Country.UA, 40.0F);
		discardCountryFighters(level, Country.UA);
		setCoreHp(server, Country.KZ, 0.0F);
		discardCountryFighters(level, Country.KZ);
		ArenaCoreRescueManager.get().tick(server);
		ArenaCoreRescueManager.get().forceTestElimination(server, Country.US);

		ArenaRoundHudSync.pushNow(server);
		return "HUD twenty states test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "20 стран: RU щит, UA уязвима, KZ спасение, US выбыла, разные HP%.\n"
				+ "Проверьте HUD v3: 4 колонки × 5 строк, /arena_hud_debug_client.";
	}

	private static void startCoreAfterLastDefender(MinecraftServer server, ServerLevel level, Vec3 origin, UUID playerId) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.spawnTestFightersOnField(server, level, Country.RU, 1);
		match.spawnTestFightersOnField(server, level, Country.UA, 1);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		for (Entity entity : fightLevel.getAllEntities()) {
			if (entity instanceof ArenaFighterEntity fighter
					&& FighterFactory.isArenaFighter(fighter)
					&& fighter.getArenaCountry() == Country.UA) {
				fighter.setHealth(1.0F);
			}
		}
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();
		ArenaRoundHudSync.pushNow(server);

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "core_after_last_defender";
		scenario.phase = DeferredPhase.CORE_AFTER_DEFENDER_WAIT;
		scenario.playerId = playerId;
		scenario.origin = origin;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = 5 * 20;
		deferred = scenario;
	}

	private static String evaluateCoreAfterLastDefender(MinecraftServer server, ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		java.util.List<String> errors = new java.util.ArrayList<>();
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);

		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (uaLive != 0) {
			errors.add("UA living=" + uaLive);
		}
		if (ArenaCoreManager.get().isCoreProtected(level, Country.UA)) {
			errors.add("UA protected=true");
		}

		ArenaFighterEntity ruFighter = null;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ArenaFighterEntity fighter
					&& FighterFactory.isArenaFighter(fighter)
					&& fighter.isAlive()
					&& fighter.getArenaCountry() == Country.RU) {
				ruFighter = fighter;
				break;
			}
		}
		if (ruFighter == null) {
			errors.add("RU fighter missing");
		} else {
			Country coreTarget = ArenaCoreCombatManager.get().getCoreTarget(ruFighter.getUUID());
			if (coreTarget != Country.UA) {
				errors.add("RU coreTarget=" + coreTarget);
			}
			BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.UA);
			if (!ArenaLayoutPathfinder.hasNavigationPathToTarget(level, ruFighter.blockPosition(), approach)) {
				errors.add("no path to UA approach");
			}
		}

		double ruCoreDamage = ArenaCoreManager.get().getCoreDamageDealt(Country.RU);
		if (ruCoreDamage <= 0.0D) {
			errors.add("RU coreDamageDealt=0");
		}
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		if (uaCore.getCurrentHealth() >= uaCore.getMaxHealth()) {
			errors.add("UA core HP unchanged");
		}

		if (errors.isEmpty()) {
			return "SUCCESS: UA eliminated, RU attacked UA core, damage="
					+ ArenaCoreManager.formatHealth((float) ruCoreDamage)
					+ ", UA HP="
					+ ArenaCoreManager.formatHealth(uaCore.getCurrentHealth());
		}
		return "FAILURE: " + String.join("; ", errors);
	}

	private static String runOverlayTwenty(MinecraftServer server, ServerLevel level, Vec3 origin) {
		String summary = runTwentyCountriesMass(server, level, origin);
		ArenaOverlayStateService.pushNow(server);
		int snapshotCountries = ArenaOverlayStateService.get().snapshotCountryCount();
		int active = ArenaMatchManager.get().getActiveCountries().size();
		String overlayCheck = snapshotCountries == 20 && active == 20
				? "\nOVERLAY VALIDATION OK: 20 countries in snapshot."
				: "\nOVERLAY VALIDATION FAILED: snapshot=" + snapshotCountries + " active=" + active;
		return summary + overlayCheck;
	}

	private static String runBaseDisplayTest(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		Country[] eight = {
				Country.RU, Country.UA, Country.KZ, Country.PL,
				Country.US, Country.CN, Country.IL, Country.GE
		};
		match.prepareTestBattle(server, level, origin, eight);
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();

		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		match.spawnTestFightersOnField(server, level, Country.RU, 3);
		match.spawnTestFightersOnField(server, level, Country.KZ, 2);
		discardCountryFighters(fightLevel, Country.UA);
		discardCountryFighters(fightLevel, Country.PL);
		setCoreHp(server, Country.PL, 55.0F);
		setCoreHp(server, Country.CN, 120.0F);
		setCoreHp(server, Country.US, 0.0F);
		discardCountryFighters(fightLevel, Country.US);
		ArenaCoreRescueManager.get().forceTestElimination(server, Country.US);
		setCoreHp(server, Country.IL, 0.0F);
		discardCountryFighters(fightLevel, Country.IL);
		ArenaCoreRescueManager.get().forceTestRescue(server, Country.IL);

		ArenaCoreDisplayManager.get().refreshActiveCountries(fightLevel, center);
		ArenaRoundHudSync.pushNow(server);
		return "Base display test (client markers)\n"
				+ "8 баз: RU=ЩИТ, UA=УЯЗВИМА, PL=55HP, CN=среднее HP, US=ВЫБЫЛА, IL=СПАСЕНИЕ, KZ/GE активны.\n"
				+ "Проверьте крупный флаг + название + HP над базами. Legacy TextDisplay отключён.";
	}

	private static String runBaseMarkerFlagsTest(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		Country[] eight = {
				Country.RU, Country.UA, Country.IL, Country.GE,
				Country.AL, Country.KZ, Country.US, Country.CN
		};
		match.prepareTestBattle(server, level, origin, eight);
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		match.spawnTestFightersOnField(server, level, Country.RU, 2);
		discardCountryFighters(fightLevel, Country.UA);
		setCoreHp(server, Country.KZ, 140.0F);
		setCoreHp(server, Country.CN, 60.0F);
		setCoreHp(server, Country.AL, 25.0F);
		setCoreHp(server, Country.US, 0.0F);
		discardCountryFighters(fightLevel, Country.US);
		ArenaCoreRescueManager.get().forceTestElimination(server, Country.US);
		setCoreHp(server, Country.IL, 0.0F);
		discardCountryFighters(fightLevel, Country.IL);
		ArenaCoreRescueManager.get().forceTestRescue(server, Country.IL);

		ArenaRoundHudSync.pushNow(server);
		return "Base marker flags test (" + ArenaEconomyTest.BATTLE_SECONDS + " сек)\n"
				+ "8 баз без боя: RU/UA/IL/GE/AL/KZ/US/CN — разные HP/статусы.\n"
				+ "Проверьте HD-флаги (не чёрные), название, HP, статус. /arena_base_markers status";
	}

	private static String runCoreStructureIntegrity(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.spawnTestFightersOnField(server, level, Country.RU, 1);
		discardCountryFighters(ArenaSpawns.resolveFightLevel(server, level), Country.UA);
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		int uaSlot = match.getBaseSlot(Country.UA);
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> beforeBlocks = snapshotBaseBlocks(fightLevel, center, uaSlot);
		int lanternsBefore = countLanterns(beforeBlocks);
		int itemsBefore = countNearbyItems(fightLevel, ArenaCountryBaseLayout.corePosition(center, uaSlot), 18.0D);

		float hpBefore = ArenaCoreManager.get().getState(Country.UA).getCurrentHealth();
		for (int i = 0; i < 20; i++) {
			ArenaCoreManager.get().damageFromFighter(server, fightLevel, Country.UA, Country.RU, 2.5F);
		}
		float hpAfter = ArenaCoreManager.get().getState(Country.UA).getCurrentHealth();
		ArenaRoundHudSync.pushNow(server);

		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> afterBlocks = snapshotBaseBlocks(fightLevel, center, uaSlot);
		int lanternsAfter = countLanterns(afterBlocks);
		int itemsAfter = countNearbyItems(fightLevel, ArenaCountryBaseLayout.corePosition(center, uaSlot), 18.0D);

		java.util.List<String> changes = new java.util.ArrayList<>();
		for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry : beforeBlocks.entrySet()) {
			net.minecraft.world.level.block.state.BlockState now = afterBlocks.get(entry.getKey());
			if (now == null || !now.equals(entry.getValue())) {
				changes.add(entry.getKey().toShortString()
						+ " was=" + entry.getValue()
						+ " now=" + now);
			}
		}
		for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry : afterBlocks.entrySet()) {
			if (!beforeBlocks.containsKey(entry.getKey())) {
				changes.add(entry.getKey().toShortString() + " added=" + entry.getValue());
			}
		}

		boolean hpChanged = hpAfter < hpBefore - 0.1F;
		int changedBlocks = changes.size();
		int missingLanterns = Math.max(0, lanternsBefore - lanternsAfter);
		int newItems = Math.max(0, itemsAfter - itemsBefore);
		boolean pass = hpChanged && changedBlocks == 0 && missingLanterns == 0 && newItems == 0;

		StringBuilder report = new StringBuilder();
		report.append(pass ? "CORE STRUCTURE INTEGRITY: PASS" : "CORE STRUCTURE INTEGRITY: FAIL").append('\n');
		report.append("coreHpChanged=").append(hpChanged)
				.append(" (").append(Math.round(hpBefore)).append("→").append(Math.round(hpAfter)).append(")\n");
		report.append("changedBlocks=").append(changedBlocks).append('\n');
		report.append("missingLanterns=").append(missingLanterns)
				.append(" (before=").append(lanternsBefore).append(" after=").append(lanternsAfter).append(")\n");
		report.append("newItemEntities=").append(newItems).append('\n');
		if (!changes.isEmpty()) {
			report.append("firstChanges:\n");
			for (int i = 0; i < Math.min(8, changes.size()); i++) {
				report.append("  ").append(changes.get(i)).append('\n');
			}
		}
		return report.toString().trim();
	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snapshotBaseBlocks(
			ServerLevel level,
			BlockPos arenaCenter,
			int slot) {
		BlockPos core = ArenaCountryBaseLayout.corePosition(arenaCenter, slot);
		net.minecraft.core.Direction outward = ArenaCountryBaseLayout.outwardDirection(slot);
		net.minecraft.core.Direction side = outward.getClockWise();
		BlockPos footing = core.below();
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> map = new java.util.LinkedHashMap<>();
		for (int o = -2; o <= 3; o++) {
			for (int s = -7; s <= 7; s++) {
				for (int h = 0; h <= 12; h++) {
					BlockPos pos = footing.relative(outward, o).relative(side, s).above(h);
					map.put(pos.immutable(), level.getBlockState(pos));
				}
			}
		}
		return map;
	}

	private static int countLanterns(java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
		int count = 0;
		for (net.minecraft.world.level.block.state.BlockState state : blocks.values()) {
			if (state.is(net.minecraft.world.level.block.Blocks.LANTERN)
					|| state.is(net.minecraft.world.level.block.Blocks.SOUL_LANTERN)) {
				count++;
			}
		}
		return count;
	}

	private static int countNearbyItems(ServerLevel level, BlockPos center, double radius) {
		double r2 = radius * radius;
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
				double dx = entity.getX() - (center.getX() + 0.5D);
				double dy = entity.getY() - (center.getY() + 0.5D);
				double dz = entity.getZ() - (center.getZ() + 0.5D);
				if (dx * dx + dy * dy + dz * dz <= r2) {
					count++;
				}
			}
		}
		return count;
	}

	private static String runOverlayTikTokTest(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);
		match.prepareTestBattle(server, level, origin, Country.ALL.toArray(Country[]::new));
		match.startPreparedTestBattle(server);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		int index = 0;
		for (Country country : Country.ALL) {
			int active = 1 + (index % 5);
			int reserveCoins = 2 + (index % 6);
			match.spawnTestFightersOnField(server, level, country, active);
			match.handleGift(server, fightLevel, origin, country, reserveCoins);
			float max = ArenaCoreManager.get().getState(country).getMaxHealth();
			float hp = switch (index % 5) {
				case 0 -> max;
				case 1 -> max * 0.7F;
				case 2 -> max * 0.4F;
				case 3 -> max * 0.15F;
				default -> 0.0F;
			};
			setCoreHp(server, country, hp);
			index++;
		}
		discardCountryFighters(fightLevel, Country.US);
		ArenaCoreRescueManager.get().forceTestElimination(server, Country.US);
		discardCountryFighters(fightLevel, Country.AL);
		setCoreHp(server, Country.AL, 0.0F);
		ArenaCoreRescueManager.get().forceTestRescue(server, Country.AL);

		ArenaRoundHudSync.pushNow(server);
		ArenaOverlayStateService.pushNow(server);
		return "TikTok overlay test\n"
				+ "20 стран, разные fighters/reserve/HP/status, BATTLE 180с без массового боя.\n"
				+ "Browser Source: " + ArenaStreamToEarnHttpBridge.getTikTokOverlayUrl()
				+ "\nPreview: " + ArenaStreamToEarnHttpBridge.getTikTokOverlayUrl() + "?preview=1";
	}

	private static String runFighterFlagsTest(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA);
		match.registerTestCountries(server, Country.ALL.toArray(Country[]::new));
		for (Country country : Country.ALL) {
			match.spawnTestFightersOnField(server, level, country, 1);
		}
		match.startPreparedTestBattle(server);

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		double radius = 22.0D;
		int index = 0;
		for (Country country : Country.ALL) {
			double angle = Math.toRadians(index * (360.0D / Country.ALL.size()));
			double x = center.getX() + 0.5D + Math.sin(angle) * radius;
			double z = center.getZ() + 0.5D - Math.cos(angle) * radius;
			for (Entity entity : fightLevel.getAllEntities()) {
				if (entity instanceof ArenaFighterEntity fighter
						&& FighterFactory.isArenaFighter(fighter)
						&& fighter.getArenaCountry() == country
						&& fighter.isAlive()) {
					fighter.teleportTo(x, center.getY() + 1.0D, z);
					fighter.setNoAi(true);
					fighter.setTarget(null);
					break;
				}
			}
			index++;
		}
		ArenaRoundHudSync.pushNow(server);
		return "Fighter flags test\n"
				+ "20 бойцов по дуге радиуса "
				+ radius
				+ " — визуальная проверка PNG-флагов 128×80.";
	}

	private static String runLayoutSpawnTest(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager match = ArenaMatchManager.get();
		match.reset(server);
		match.registerTestCountries(server, Country.ALL.toArray(Country[]::new));

		BlockPos center = BlockPos.containing(origin.x, origin.y, origin.z);
		if (!match.getMatchCenter().equals(Vec3.ZERO)) {
			center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		}
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);

		java.util.List<String> errors = new java.util.ArrayList<>();
		int spawned = 0;
		java.util.List<ArenaFighterEntity> probes = new java.util.ArrayList<>();

		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			java.util.List<BlockPos> safeFeet = ArenaCountryBaseLayout.collectSafeSpawnFeet(fightLevel, center, slot);
			if (safeFeet.size() < ArenaCountryBaseLayout.MIN_SAFE_SPAWN_POINTS) {
				errors.add("slot " + slot + " safe=" + safeFeet.size());
			}
			for (BlockPos feet : safeFeet) {
				ArenaFighterEntity probe = ArenaEntities.ARENA_FIGHTER.create(fightLevel);
				if (probe == null) {
					errors.add("slot " + slot + " probe null");
					continue;
				}
				probe.addTag(ArenaLayoutPathfinder.TEST_PROBE_TAG);
				probe.setNoAi(true);
				probe.setPos(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
				fightLevel.addFreshEntity(probe);
				probes.add(probe);
				spawned++;

				if (!ArenaPositions.isValidSpawn(fightLevel, center, feet)) {
					errors.add("slot " + slot + " invalid spawn at " + feet.toShortString());
				}
			}
			if (!safeFeet.isEmpty()
					&& !ArenaLayoutPathfinder.hasNavigationPathToCenter(fightLevel, center, safeFeet.get(0))) {
				errors.add("slot " + slot + " no path");
			}
		}

		for (ArenaFighterEntity probe : probes) {
			probe.discard();
		}

		if (errors.isEmpty()) {
			return "layout_spawn_test OK: checked " + spawned + " spawn points across 20 slots, paths OK, probes cleared.";
		}
		return "layout_spawn_test FAILED: " + String.join("; ", errors) + " (spawned " + spawned + ", cleared).";
	}

	private static String runCountriesJoining(MinecraftServer server, ServerLevel level, Vec3 origin) {
		beginEconomyTest(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		match.beginTestBattle(server, level, origin, Country.RU, Country.UA);
		match.spawnTestFightersOnField(server, level, Country.RU, 1);
		match.spawnTestFightersOnField(server, level, Country.UA, 1);
		int ruSlotBefore = match.getBaseSlot(Country.RU);
		int uaSlotBefore = match.getBaseSlot(Country.UA);
		Country[] lateJoin = {Country.KZ, Country.BY, Country.LT, Country.PL, Country.IL};
		for (Country country : lateJoin) {
			match.registerTestCountries(server, country);
			match.spawnTestFightersOnField(server, level, country, 1);
		}
		boolean slotsStable = match.getBaseSlot(Country.RU) == ruSlotBefore
				&& match.getBaseSlot(Country.UA) == uaSlotBefore;
		ArenaRoundHudSync.pushNow(server);
		return "Countries joining test\n"
				+ "RU/UA старт → BATTLE → поздний вход KZ/BY/LT/PL/IL.\n"
				+ "RU slot stable=" + slotsStable + " (" + ruSlotBefore + "→" + match.getBaseSlot(Country.RU) + ")\n"
				+ "UA slot stable=" + (match.getBaseSlot(Country.UA) == uaSlotBefore) + "\n"
				+ "active=" + match.getActiveCountries().size() + "/20\n"
				+ "Проверка 21-й: /arena_gift am 1 при 20 активных (лимит).";
	}

	private static String validateTwentyCountries(
			MinecraftServer server,
			ServerLevel fightLevel,
			ArenaMatchManager match,
			int expectedPerCountry,
			int expectedReserve) {
		java.util.List<String> errors = new java.util.ArrayList<>();
		if (match.getState() != ArenaMatchState.BATTLE) {
			errors.add("state=" + match.getState());
		}
		if (match.getActiveCountries().size() != Country.SUPPORTED_COUNT) {
			errors.add("active=" + match.getActiveCountries().size());
		}
		if (match.getCountryBaseSlots().size() != Country.SUPPORTED_COUNT) {
			errors.add("slots=" + match.getCountryBaseSlots().size());
		}
		java.util.Set<Integer> slots = new java.util.HashSet<>(match.getCountryBaseSlots().values());
		if (slots.size() != Country.SUPPORTED_COUNT) {
			errors.add("unique slots=" + slots.size());
		}
		java.util.Set<BlockPos> cores = new java.util.HashSet<>();
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		for (int slot : match.getCountryBaseSlots().values()) {
			cores.add(ArenaCountryBaseLayout.corePosition(center, slot));
		}
		if (cores.size() != Country.SUPPORTED_COUNT) {
			errors.add("unique cores=" + cores.size());
		}
		for (Country country : Country.ALL) {
			int live = match.countLivingFightersUncached(fightLevel, country);
			if (live != expectedPerCountry) {
				errors.add(country.getCode() + " live=" + live);
			}
			if (match.getReserveSize(country) != expectedReserve) {
				errors.add(country.getCode() + " reserve=" + match.getReserveSize(country));
			}
		}
		if (errors.isEmpty()) {
			return "VALIDATION OK.";
		}
		String message = String.join("; ", errors);
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[twenty_countries] VALIDATION FAILED: " + message),
				false);
		return "VALIDATION FAILED: " + message;
	}

	private static String runHudDemo(MinecraftServer server, ServerLevel level, Vec3 origin) {
		ArenaMatchManager.get().reset(server);
		giftMany(server, level, origin, Country.RU, FighterTier.SCOUT, 6);
		giftMany(server, level, origin, Country.UA, FighterTier.SCOUT, 4);
		giftMany(server, level, origin, Country.KZ, FighterTier.SCOUT, 3);
		giftMany(server, level, origin, Country.BY, FighterTier.SCOUT, 2);

		setCoreHp(server, Country.RU, 200.0F);
		setCoreHp(server, Country.UA, 150.0F);
		setCoreHp(server, Country.KZ, 100.0F);
		setCoreHp(server, Country.BY, 50.0F);

		return "HUD demo запущен. Проверь главную полосу и четыре полосы стран.";
	}

	private static void setCoreHp(MinecraftServer server, Country country, float targetHp) {
		ArenaCoreState state = ArenaCoreManager.get().getState(country);
		if (!state.isActive()) {
			ArenaCoreManager.get().activate(server, country);
			state = ArenaCoreManager.get().getState(country);
		}
		float current = state.getCurrentHealth();
		if (current > targetHp) {
			ArenaCoreManager.get().damage(server, country, current - targetHp);
		} else if (current < targetHp) {
			ArenaCoreManager.get().heal(server, country, targetHp - current);
		}
	}

	private static void startCoreRescue(MinecraftServer server, ServerLevel level, Vec3 origin, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		gift(server, level, origin, Country.RU, FighterTier.SCOUT);
		gift(server, level, origin, Country.UA, FighterTier.SCOUT);

		ArenaCoreState ruCore = ArenaCoreManager.get().getState(Country.RU);
		ArenaCoreManager.get().damage(server, Country.RU, ruCore.getCurrentHealth());

		if (ArenaCoreRescueManager.get().isRescuing(Country.RU)) {
			finishDeferred(server, playerId, "core_rescue",
					"FAILURE: countdown запустился при живых бойцах RU (ядро 0 + fighters>0 → не должно).");
			return;
		}

		discardCountryFighters(level, Country.RU);
		ArenaCoreRescueManager.get().tick(server);

		if (!ArenaCoreRescueManager.get().isRescuing(Country.RU)) {
			finishDeferred(server, playerId, "core_rescue",
					"FAILURE: countdown не запустился после ядра 0 и 0 бойцов RU.");
			return;
		}

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "core_rescue";
		scenario.phase = DeferredPhase.RESCUE_WAIT_GIFT;
		scenario.playerId = playerId;
		scenario.origin = origin;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = 5 * 20;
		deferred = scenario;
	}

	private static void startCoreElimination(MinecraftServer server, ServerLevel level, Vec3 origin, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		gift(server, level, origin, Country.RU, FighterTier.SCOUT);
		gift(server, level, origin, Country.UA, FighterTier.SCOUT);

		int uaScoreBefore = ArenaScoreManager.getScore(server, Country.UA);

		ArenaCoreState ruCore = ArenaCoreManager.get().getState(Country.RU);
		ArenaCoreManager.get().damage(server, Country.RU, ruCore.getCurrentHealth());

		if (ArenaCoreRescueManager.get().isRescuing(Country.RU)) {
			finishDeferred(server, playerId, "core_elimination",
					"FAILURE: countdown запустился при живых бойцах RU.");
			return;
		}

		discardCountryFighters(level, Country.RU);
		ArenaCoreRescueManager.get().tick(server);

		if (!ArenaCoreRescueManager.get().isRescuing(Country.RU)) {
			finishDeferred(server, playerId, "core_elimination",
					"FAILURE: countdown не запустился после ядра 0 и 0 бойцов RU.");
			return;
		}

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "core_elimination";
		scenario.phase = DeferredPhase.ELIMINATION_WAIT;
		scenario.playerId = playerId;
		scenario.origin = origin;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = ArenaConfig.get().getCoreRescueSeconds() * 20 + 5;
		scenario.uaScoreBefore = uaScoreBefore;
		deferred = scenario;
	}

	private static void discardCountryFighters(ServerLevel level, Country country) {
		List<Entity> toRemove = new java.util.ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ArenaFighterEntity fighter
					&& FighterFactory.isArenaFighter(fighter)
					&& fighter.getArenaCountry() == country) {
				toRemove.add(fighter);
			}
		}
		for (Entity entity : toRemove) {
			entity.discard();
		}
	}

	private static void startViewerFlow(MinecraftServer server, ServerLevel level, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		ArenaViewerEventManager.get().clearTransientState();

		ArenaViewerEventManager.get().enqueueChat("viewer_ru", "viewer_ru", "!ru", null);
		ArenaViewerEventManager.get().enqueueChat("viewer_ua", "viewer_ua", "!ua", null);

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "viewer_flow";
		scenario.phase = DeferredPhase.VIEWER_FLOW_WAIT_CHAT;
		scenario.playerId = playerId;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = 2;
		deferred = scenario;
	}

	private static void startViewerDuplicate(MinecraftServer server, ServerLevel level, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		ArenaViewerEventManager.get().clearTransientState();

		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		viewers.enqueueChat("viewer_test", "viewer_test", "!ru", null);

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "viewer_duplicate";
		scenario.phase = DeferredPhase.VIEWER_DUP_WAIT_CHAT;
		scenario.playerId = playerId;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = 2;
		scenario.acceptedGiftsBefore = viewers.getAcceptedGifts();
		scenario.duplicatesBefore = viewers.getDuplicateGifts();
		deferred = scenario;
	}

	/**
	 * @return failure message if payload parse failed immediately; otherwise {@code null}
	 */
	private static String startS2eBridge(MinecraftServer server, ServerLevel level, UUID playerId) {
		ArenaMatchManager.get().reset(server);
		ArenaViewerEventManager.get().clearTransientState();
		ArenaStreamToEarnCommands.clearBridgeCounters();

		ArenaStreamToEarnCommands.AcceptResult chatRu =
				ArenaStreamToEarnCommands.acceptChatPayload("viewer_s2e|||!ru");
		ArenaStreamToEarnCommands.AcceptResult chatUa =
				ArenaStreamToEarnCommands.acceptChatPayload("viewer_enemy|||!ua");
		ArenaStreamToEarnCommands.AcceptResult giftRu =
				ArenaStreamToEarnCommands.acceptGiftPayload("viewer_s2e|||1|||s2e_test_ru");
		ArenaStreamToEarnCommands.AcceptResult giftUa =
				ArenaStreamToEarnCommands.acceptGiftPayload("viewer_enemy|||50|||s2e_test_ua");

		if (!chatRu.accepted() || !chatUa.accepted() || !giftRu.accepted() || !giftUa.accepted()) {
			return "FAILURE: разбор payload отклонён"
					+ " chatRu=" + describeAccept(chatRu)
					+ " chatUa=" + describeAccept(chatUa)
					+ " giftRu=" + describeAccept(giftRu)
					+ " giftUa=" + describeAccept(giftUa);
		}

		DeferredScenario scenario = new DeferredScenario();
		scenario.name = "s2e_bridge";
		scenario.phase = DeferredPhase.S2E_BRIDGE_WAIT;
		scenario.playerId = playerId;
		scenario.levelKey = level.dimension().location().toString();
		scenario.ticksRemaining = 2;
		deferred = scenario;
		return null;
	}

	private static String describeAccept(ArenaStreamToEarnCommands.AcceptResult result) {
		return result.accepted() ? "OK" : ("REJECTED:" + result.reason());
	}

	private static void tickDeferred(MinecraftServer server) {
		if (ArenaFullCountryLifecycleTest.get().isRunning()) {
			ArenaFullCountryLifecycleTest.get().tick(server);
			return;
		}
		if (ArenaMassDuelReserveTest.get().isRunning()) {
			ArenaMassDuelReserveTest.get().tick(server);
			return;
		}
		if (ArenaS2eLocalGiftTest.get().isRunning()) {
			ArenaS2eLocalGiftTest.get().tick(server);
			return;
		}

		DeferredScenario scenario = deferred;
		if (scenario == null) {
			return;
		}

		scenario.ticksRemaining--;
		if (scenario.ticksRemaining > 0) {
			return;
		}

		ServerLevel level = resolveLevel(server, scenario.levelKey);
		if (level == null) {
			finishDeferred(server, scenario.playerId, scenario.name, "FAILURE: измерение сценария недоступно.");
			return;
		}

		if (scenario.phase == DeferredPhase.RESCUE_WAIT_GIFT) {
			gift(server, level, scenario.origin, Country.RU, FighterTier.SCOUT);
			finishDeferred(server, scenario.playerId, scenario.name, evaluateCoreRescue(server, level));
			return;
		}

		if (scenario.phase == DeferredPhase.ELIMINATION_WAIT) {
			finishDeferred(server, scenario.playerId, scenario.name, evaluateCoreElimination(server, level, scenario));
			return;
		}

		if (scenario.phase == DeferredPhase.VIEWER_FLOW_WAIT_CHAT) {
			ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
			if (viewers.getSelectedCountry("viewer_ru") != Country.RU
					|| viewers.getSelectedCountry("viewer_ua") != Country.UA) {
				finishDeferred(server, scenario.playerId, scenario.name,
						"FAILURE: выбор стран после chat не применился (RU="
								+ viewers.getSelectedCountry("viewer_ru")
								+ ", UA="
								+ viewers.getSelectedCountry("viewer_ua")
								+ ").");
				return;
			}

			viewers.enqueueGift("viewer_ru", "viewer_ru", 1, "test_ru_1");
			viewers.enqueueGift("viewer_ua", "viewer_ua", 50, "test_ua_1");
			scenario.phase = DeferredPhase.VIEWER_FLOW_WAIT_GIFTS;
			scenario.ticksRemaining = 2;
			return;
		}

		if (scenario.phase == DeferredPhase.VIEWER_FLOW_WAIT_GIFTS) {
			finishDeferred(server, scenario.playerId, scenario.name, evaluateViewerFlow(server, level));
			return;
		}

		if (scenario.phase == DeferredPhase.VIEWER_DUP_WAIT_CHAT) {
			ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
			if (viewers.getSelectedCountry("viewer_test") != Country.RU) {
				finishDeferred(server, scenario.playerId, scenario.name,
						"FAILURE: viewer_test не выбрал Россию.");
				return;
			}

			viewers.enqueueGift("viewer_test", "viewer_test", 1, "duplicate_1");
			viewers.enqueueGift("viewer_test", "viewer_test", 1, "duplicate_1");
			scenario.phase = DeferredPhase.VIEWER_DUP_WAIT_GIFTS;
			scenario.ticksRemaining = 2;
			return;
		}

		if (scenario.phase == DeferredPhase.VIEWER_DUP_WAIT_GIFTS) {
			finishDeferred(server, scenario.playerId, scenario.name, evaluateViewerDuplicate(server, level, scenario));
			return;
		}

		if (scenario.phase == DeferredPhase.S2E_BRIDGE_WAIT) {
			finishDeferred(server, scenario.playerId, scenario.name, evaluateS2eBridge(server, level));
			return;
		}

		if (scenario.phase == DeferredPhase.CORE_AFTER_DEFENDER_WAIT) {
			finishDeferred(server, scenario.playerId, scenario.name, evaluateCoreAfterLastDefender(server, level));
		}
	}

	private static String evaluateS2eBridge(MinecraftServer server, ServerLevel level) {
		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		ArenaMatchManager match = ArenaMatchManager.get();

		StringBuilder failure = new StringBuilder();
		if (viewers.getSelectedCountry("viewer_s2e") != Country.RU) {
			failure.append(" viewer_s2e не выбрал Россию;");
		}
		if (viewers.getSelectedCountry("viewer_enemy") != Country.UA) {
			failure.append(" viewer_enemy не выбрал Украину;");
		}
		if (viewers.getAcceptedGifts() < 2) {
			failure.append(" принятые gifts=").append(viewers.getAcceptedGifts()).append(" (ожидалось ≥2);");
		}
		if (match.getGiftCount(Country.RU) < 1 || match.getGiftCount(Country.UA) < 1) {
			failure.append(" handleGift не принял обе страны;");
		}
		if (match.getState() != ArenaMatchState.BATTLE) {
			failure.append(" матч не в BATTLE (").append(match.getState()).append(");");
		}
		int ruTotal = match.countLivingFightersUncached(level, Country.RU) + match.getReserveSize(Country.RU);
		int uaTotal = match.countLivingFightersUncached(level, Country.UA) + match.getReserveSize(Country.UA);
		if (ruTotal < 1) {
			failure.append(" нет бойцов RU (living+reserve);");
		}
		if (uaTotal < 1) {
			failure.append(" нет бойцов UA (living+reserve);");
		}

		if (failure.isEmpty()) {
			return "SUCCESS: S2E bridge → выбор стран, подарки, BATTLE.";
		}
		return "FAILURE:" + failure;
	}

	private static String evaluateViewerFlow(MinecraftServer server, ServerLevel level) {
		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaConfig config = ArenaConfig.get();

		FighterTier expectedRu = config.tierFromCoins(1);
		FighterTier expectedUa = config.tierFromCoins(50);

		StringBuilder failure = new StringBuilder();
		if (viewers.getSelectedCountry("viewer_ru") != Country.RU) {
			failure.append(" viewer_ru не выбрал Россию;");
		}
		if (viewers.getSelectedCountry("viewer_ua") != Country.UA) {
			failure.append(" viewer_ua не выбрал Украину;");
		}
		if (viewers.getAcceptedGifts() < 2) {
			failure.append(" принятые gifts=").append(viewers.getAcceptedGifts()).append(" (ожидалось ≥2);");
		}
		if (match.getGiftCount(Country.RU) < 1 || match.getGiftCount(Country.UA) < 1) {
			failure.append(" handleGift не принял обе страны (RU gifts=")
					.append(match.getGiftCount(Country.RU))
					.append(", UA gifts=")
					.append(match.getGiftCount(Country.UA))
					.append(");");
		}
		if (match.getState() != ArenaMatchState.BATTLE && match.getState() != ArenaMatchState.WAITING_FOR_OPPONENT) {
			failure.append(" матч не запущен через handleGift (").append(match.getState()).append(");");
		}
		int ruExpected = countFighters(level, Country.RU, expectedRu);
		int uaExpected = countFighters(level, Country.UA, expectedUa);
		if (ruExpected < 1) {
			failure.append(" нет бойца RU ").append(expectedRu.getDisplayName()).append(';');
		}
		if (uaExpected < 1) {
			failure.append(" нет бойца UA ").append(expectedUa.getDisplayName()).append(';');
		}

		if (failure.isEmpty()) {
			return "SUCCESS: выбор стран OK, gifts приняты, матч="
					+ match.getState()
					+ ", RU "
					+ expectedRu.name()
					+ ", UA "
					+ expectedUa.name()
					+ ".";
		}
		return "FAILURE:" + failure;
	}

	private static String evaluateViewerDuplicate(MinecraftServer server, ServerLevel level, DeferredScenario scenario) {
		ArenaViewerEventManager viewers = ArenaViewerEventManager.get();
		long acceptedDelta = viewers.getAcceptedGifts() - scenario.acceptedGiftsBefore;
		long duplicateDelta = viewers.getDuplicateGifts() - scenario.duplicatesBefore;
		int ruFighters = ArenaMatchManager.get().countLivingFighters(level, Country.RU);
		int ruGifts = ArenaMatchManager.get().getGiftCount(Country.RU);

		StringBuilder failure = new StringBuilder();
		if (acceptedDelta != 1) {
			failure.append(" принятых gifts delta=").append(acceptedDelta).append(" (ожидалось 1);");
		}
		if (duplicateDelta != 1) {
			failure.append(" duplicates delta=").append(duplicateDelta).append(" (ожидалось 1);");
		}
		if (ruGifts != 1) {
			failure.append(" giftCount RU=").append(ruGifts).append(" (ожидалось 1);");
		}
		if (ruFighters != 1) {
			failure.append(" бойцов RU=").append(ruFighters).append(" (ожидалось 1);");
		}

		if (failure.isEmpty()) {
			return "SUCCESS: подарок принят 1 раз, duplicate=+1, боец RU=1.";
		}
		return "FAILURE:" + failure;
	}

	private static int countFighters(ServerLevel level, Country country, FighterTier tier) {
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof LivingEntity living)
					|| !living.isAlive()
					|| !FighterFactory.isArenaFighter(living)
					|| FighterFactory.getCountry(living) != country) {
				continue;
			}
			if (living.getTags().contains(tier.tierTag())) {
				count++;
			}
		}
		return count;
	}

	private static ArenaFighterEntity findFighter(ServerLevel level, Country country, FighterTier tier) {
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| fighter.getArenaCountry() != country
					|| fighter.getArenaTier() != tier) {
				continue;
			}
			return fighter;
		}
		return null;
	}

	private static List<ArenaFighterEntity> findFighters(ServerLevel level, Country country, FighterTier tier, int maxCount) {
		java.util.ArrayList<ArenaFighterEntity> fighters = new java.util.ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| fighter.getArenaCountry() != country
					|| fighter.getArenaTier() != tier) {
				continue;
			}
			fighters.add(fighter);
			if (fighters.size() >= maxCount) {
				break;
			}
		}
		return fighters;
	}

	private static String evaluateCoreRescue(MinecraftServer server, ServerLevel level) {
		ArenaCoreState ruCore = ArenaCoreManager.get().getState(Country.RU);
		float expected = ruCore.getMaxHealth() * (ArenaConfig.get().getCoreRescueHealthPercent() / 100.0F);
		boolean rescueOff = !ArenaCoreRescueManager.get().isRescuing(Country.RU);
		boolean notEliminated = !ArenaCoreRescueManager.get().isEliminated(Country.RU);
		boolean notDestroyed = !ruCore.isDestroyed();
		boolean hpOk = Math.abs(ruCore.getCurrentHealth() - expected) < 0.05F;
		int living = ArenaMatchManager.get().countLivingFighters(level, Country.RU);

		if (rescueOff && notEliminated && notDestroyed && hpOk && living >= 1) {
			return "SUCCESS: countdown=false, eliminated=false, destroyed=false, HP="
					+ ArenaCoreManager.formatHealth(ruCore.getCurrentHealth())
					+ " (ожидалось "
					+ ArenaCoreManager.formatHealth(expected)
					+ "), бойцы RU=" + living;
		}

		StringBuilder failure = new StringBuilder("FAILURE:");
		if (!rescueOff) {
			failure.append(" countdown всё ещё true;");
		}
		if (!notEliminated) {
			failure.append(" eliminated=true;");
		}
		if (!notDestroyed) {
			failure.append(" destroyed=true;");
		}
		if (!hpOk) {
			failure.append(" HP=")
					.append(ArenaCoreManager.formatHealth(ruCore.getCurrentHealth()))
					.append(" ожидалось ")
					.append(ArenaCoreManager.formatHealth(expected))
					.append(';');
		}
		if (living < 1) {
			failure.append(" боец после подарка не создан;");
		}
		return failure.toString();
	}

	private static String evaluateCoreElimination(MinecraftServer server, ServerLevel level, DeferredScenario scenario) {
		boolean eliminated = scenario.sawRuEliminated;
		int ruLiving = ArenaMatchManager.get().countLivingFighters(level, Country.RU);
		int ruReserve = ArenaMatchManager.get().getReserveSize(Country.RU);
		ArenaMatchState matchState = ArenaMatchManager.get().getState();
		int uaScoreAfter = ArenaScoreManager.getScore(server, Country.UA);
		int expectedGain = 3;

		StringBuilder failure = new StringBuilder();
		if (!eliminated) {
			failure.append(" Россия не eliminated;");
		}
		if (ruLiving != 0) {
			failure.append(" бойцы RU не удалены (").append(ruLiving).append(");");
		}
		if (ruReserve != 0) {
			failure.append(" резерв RU не очищен (").append(ruReserve).append(");");
		}
		if (matchState != ArenaMatchState.BREAK) {
			failure.append(" матч не в BREAK (").append(matchState).append(");");
		}
		if (uaScoreAfter != scenario.uaScoreBefore + expectedGain) {
			failure.append(" очки UA ")
					.append(scenario.uaScoreBefore)
					.append("→")
					.append(uaScoreAfter)
					.append(" (ожидалось +")
					.append(expectedGain)
					.append(");");
		}

		if (failure.isEmpty()) {
			return "SUCCESS: RU eliminated, бойцы/резерв очищены, UA победила (+"
					+ expectedGain + "), матч BREAK.";
		}
		return "FAILURE:" + failure;
	}

	private static void finishDeferred(MinecraftServer server, UUID playerId, String name, String result) {
		deferred = null;
		RUNNING.set(false);
		Component message = Component.literal("Сценарий: " + name + "\n" + result);
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player != null) {
			player.sendSystemMessage(message);
		} else {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
	}

	private static ServerLevel resolveLevel(MinecraftServer server, String dimensionKey) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(dimensionKey)) {
				return level;
			}
		}
		return server.overworld();
	}

	private static void giftMany(
			MinecraftServer server,
			ServerLevel level,
			Vec3 origin,
			Country country,
			FighterTier tier,
			int count) {
		for (int i = 0; i < count; i++) {
			gift(server, level, origin, country, tier);
		}
	}

	private static void gift(
			MinecraftServer server,
			ServerLevel level,
			Vec3 origin,
			Country country,
			FighterTier tier) {
		ArenaMatchManager.get().handleGift(server, level, origin, country, coinsFor(tier));
	}

	private static int coinsFor(FighterTier tier) {
		return tier.getGiftCost();
	}

	private static CompletableFuture<Suggestions> suggestScenarios(
			CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(SCENARIO_IDS, builder);
	}

	private enum DeferredPhase {
		RESCUE_WAIT_GIFT,
		ELIMINATION_WAIT,
		VIEWER_FLOW_WAIT_CHAT,
		VIEWER_FLOW_WAIT_GIFTS,
		VIEWER_DUP_WAIT_CHAT,
		VIEWER_DUP_WAIT_GIFTS,
		S2E_BRIDGE_WAIT,
		CORE_AFTER_DEFENDER_WAIT
	}

	private static final class DeferredScenario {
		private String name;
		private DeferredPhase phase;
		private UUID playerId;
		private Vec3 origin;
		private String levelKey;
		private int ticksRemaining;
		private int uaScoreBefore;
		private boolean sawRuEliminated;
		private long acceptedGiftsBefore;
		private long duplicatesBefore;
	}
}
