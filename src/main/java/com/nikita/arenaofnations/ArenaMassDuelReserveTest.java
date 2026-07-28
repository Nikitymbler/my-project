package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic mass RU/UA reserve duel: gifts → waves → march → living targets → melee.
 * Does not wait for a full 2000-fighter battle to finish.
 */
public final class ArenaMassDuelReserveTest {
	private static final ArenaMassDuelReserveTest INSTANCE = new ArenaMassDuelReserveTest();

	private static final int GIFT_EACH = 1000;
	private static final int TIMEOUT_TICKS = 90 * 20;
	private static final double LEFT_SPAWN_INWARD = 6.0;
	private static final double CLOSING_IMPROVEMENT = 8.0;

	private boolean running;
	private Stage stage = Stage.IDLE;
	private UUID playerId;
	private Vec3 origin = Vec3.ZERO;
	private String levelKey = "";
	private int elapsedTicks;
	private String lastFailure = "";
	private boolean finishedPass;

	private double initialFrontDistance = -1.0;
	private double bestFrontDistance = Double.MAX_VALUE;
	private int maxWaveSeen;

	private ArenaMassDuelReserveTest() {
	}

	public static ArenaMassDuelReserveTest get() {
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

	public String start(MinecraftServer server, ServerLevel level, Vec3 origin, UUID playerId) {
		ArenaMatchManager match = ArenaMatchManager.get();
		match.reset(server);

		this.running = true;
		this.finishedPass = false;
		this.lastFailure = "";
		this.playerId = playerId;
		this.origin = origin;
		this.levelKey = level.dimension().location().toString();
		this.elapsedTicks = 0;
		this.initialFrontDistance = -1.0;
		this.bestFrontDistance = Double.MAX_VALUE;
		this.maxWaveSeen = 0;
		this.stage = Stage.SETUP;

		match.handleGift(server, level, origin, Country.RU, GIFT_EACH);
		match.handleGift(server, level, origin, Country.UA, GIFT_EACH);

		if (match.getState() != ArenaMatchState.BATTLE) {
			return finishFail(server, "SETUP", "expected BATTLE after gifts, got " + match.getState());
		}

		int ruReserve = match.getReserveSize(Country.RU);
		int uaReserve = match.getReserveSize(Country.UA);
		if (ruReserve != GIFT_EACH || uaReserve != GIFT_EACH) {
			return finishFail(server, "SETUP", "reserve RU=" + ruReserve + " UA=" + uaReserve + " expected " + GIFT_EACH);
		}

		int ruLive = match.countLivingFightersUncached(level, Country.RU);
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (ruLive != 0 || uaLive != 0) {
			return finishFail(server, "SETUP", "fighters spawned immediately on field RU=" + ruLive + " UA=" + uaLive);
		}

		stage = Stage.WAIT_WAVE;
		return "mass_duel_reserve started: RU/UA gift " + GIFT_EACH
				+ " → reserve, BATTLE, waiting for waves + march.";
	}

	public void tick(MinecraftServer server) {
		if (!running || finishedPass) {
			return;
		}

		elapsedTicks++;
		if (elapsedTicks > TIMEOUT_TICKS) {
			finishFail(server, stage.name(), "timeout after " + elapsedTicks + " ticks");
			return;
		}

		ServerLevel level = resolveLevel(server);
		if (level == null) {
			finishFail(server, stage.name(), "level missing: " + levelKey);
			return;
		}

		ArenaMatchManager match = ArenaMatchManager.get();
		if (match.getState() != ArenaMatchState.BATTLE) {
			finishFail(server, stage.name(), "left BATTLE → " + match.getState());
			return;
		}

		int waveSize = ArenaConfig.get().getReserveWaveSize();
		int lastRu = match.getLastWaveReleased(Country.RU);
		int lastUa = match.getLastWaveReleased(Country.UA);
		maxWaveSeen = Math.max(maxWaveSeen, Math.max(lastRu, lastUa));
		if (lastRu > waveSize || lastUa > waveSize) {
			finishFail(server, stage.name(), "wave oversize RU=" + lastRu + " UA=" + lastUa + " cap=" + waveSize);
			return;
		}

		switch (stage) {
			case WAIT_WAVE -> tickWaitWave(server, level, match, waveSize);
			case LEFT_SPAWN -> tickLeftSpawn(server, level);
			case CLOSING -> tickClosing(server, level);
			case LIVING_TARGET -> tickLivingTarget(server, level);
			case MELEE -> tickMelee(server, level);
			default -> {
			}
		}
	}

	public String statusReport(MinecraftServer server) {
		StringBuilder builder = new StringBuilder("Mass duel reserve status:\n");
		builder.append("running=").append(running).append('\n');
		builder.append("result=").append(finishedPass ? "PASS" : (lastFailure.isEmpty() ? (running ? "RUNNING" : "IDLE") : "FAIL")).append('\n');
		builder.append("stage=").append(stage).append('\n');
		builder.append("elapsedTicks=").append(elapsedTicks).append('\n');
		if (!lastFailure.isEmpty()) {
			builder.append("reason=").append(lastFailure).append('\n');
		}
		builder.append(compactDiag(server));
		return builder.toString();
	}

	private void tickWaitWave(MinecraftServer server, ServerLevel level, ArenaMatchManager match, int waveSize) {
		int ruLive = match.countLivingFightersUncached(level, Country.RU);
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (ruLive < 1 || uaLive < 1) {
			return;
		}
		int lastTotal = match.getLastWaveReleasedTotal();
		if (lastTotal > waveSize * match.getActiveCountries().size()) {
			finishFail(server, "WAIT_WAVE", "last wave total=" + lastTotal + " exceeds countries×waveSize");
			return;
		}
		stage = Stage.LEFT_SPAWN;
	}

	private void tickLeftSpawn(MinecraftServer server, ServerLevel level) {
		BlockPos center = ArenaCoreCombatManager.resolveArenaCenter(server);
		if (center == null) {
			finishFail(server, "LEFT_SPAWN", "no arena center");
			return;
		}

		double ruFront = minDistanceToCenter(level, Country.RU, center);
		double uaFront = minDistanceToCenter(level, Country.UA, center);
		if (ruFront < 0 || uaFront < 0) {
			return;
		}

		double spawnRadius = ArenaCountryBaseLayout.SPAWN_ZONE_RADIUS;
		boolean ruLeft = ruFront <= spawnRadius - LEFT_SPAWN_INWARD;
		boolean uaLeft = uaFront <= spawnRadius - LEFT_SPAWN_INWARD;
		if (!ruLeft || !uaLeft) {
			return;
		}

		initialFrontDistance = nearestArmyDistance(level, Country.RU, Country.UA);
		bestFrontDistance = initialFrontDistance;
		stage = Stage.CLOSING;
	}

	private void tickClosing(MinecraftServer server, ServerLevel level) {
		double dist = nearestArmyDistance(level, Country.RU, Country.UA);
		if (dist < 0) {
			return;
		}
		bestFrontDistance = Math.min(bestFrontDistance, dist);

		if (initialFrontDistance < 0) {
			initialFrontDistance = dist;
			return;
		}

		boolean closed = bestFrontDistance <= initialFrontDistance - CLOSING_IMPROVEMENT
				|| bestFrontDistance <= FighterTargeting.getLivingSearchRadius();
		if (!closed) {
			return;
		}
		stage = Stage.LIVING_TARGET;
	}

	private void tickLivingTarget(MinecraftServer server, ServerLevel level) {
		FighterTargeting.MarchSnapshot snap = FighterTargeting.collectMarchSnapshot(level);
		if (snap.withLivingTarget < 1) {
			return;
		}
		stage = Stage.MELEE;
	}

	private void tickMelee(MinecraftServer server, ServerLevel level) {
		boolean meleeSeen = false;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}
			if (fighter.isMeleeWindupActive() || fighter.swinging) {
				meleeSeen = true;
				break;
			}
			LivingEntity target = fighter.getTarget();
			if (target instanceof ArenaFighterEntity enemy
					&& FighterFactory.isArenaFighter(enemy)
					&& fighter.distanceToSqr(enemy) <= 12.25
					&& (fighter.hurtTime > 0 || enemy.hurtTime > 0)) {
				meleeSeen = true;
				break;
			}
		}
		if (!meleeSeen) {
			FighterTargeting.MarchSnapshot snap = FighterTargeting.collectMarchSnapshot(level);
			double dist = nearestArmyDistance(level, Country.RU, Country.UA);
			if (snap.withLivingTarget >= 1 && dist >= 0 && dist <= 6.0) {
				meleeSeen = true;
			} else {
				return;
			}
		}
		finishPass(server);
	}

	private void finishPass(MinecraftServer server) {
		finishedPass = true;
		running = false;
		stage = Stage.DONE;
		lastFailure = "";
		ArenaTestScenarioCommands.onLifecycleFinished();
		notifyPlayer(server, "MASS DUEL RESERVE: PASS\n" + compactDiag(server));
	}

	private String finishFail(MinecraftServer server, String stageName, String reason) {
		finishedPass = false;
		running = false;
		stage = Stage.DONE;
		lastFailure = reason;
		ArenaTestScenarioCommands.onLifecycleFinished();
		String message = "MASS DUEL RESERVE: FAILED\nstage=" + stageName + "\nreason=" + reason + "\n" + compactDiag(server);
		notifyPlayer(server, message);
		return message;
	}

	private String compactDiag(MinecraftServer server) {
		ServerLevel level = resolveLevel(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		StringBuilder builder = new StringBuilder();
		builder.append("battleTicks=").append(match.getBattleTicksElapsed()).append('\n');
		builder.append("waveSize=").append(ArenaConfig.get().getReserveWaveSize())
				.append(" interval=").append(ArenaConfig.get().getReserveWaveIntervalTicks()).append('\n');
		builder.append("lastWave total=").append(match.getLastWaveReleasedTotal())
				.append(" RU=").append(match.getLastWaveReleased(Country.RU))
				.append(" UA=").append(match.getLastWaveReleased(Country.UA))
				.append(" maxPerCountrySeen=").append(maxWaveSeen).append('\n');

		if (level == null) {
			builder.append("level=missing\n");
			return builder.toString();
		}

		BlockPos center = ArenaCoreCombatManager.resolveArenaCenter(server);
		int ruLive = match.countLivingFightersUncached(level, Country.RU);
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		builder.append("living RU=").append(ruLive).append(" UA=").append(uaLive).append('\n');
		builder.append("reserve RU=").append(match.getReserveSize(Country.RU))
				.append(" UA=").append(match.getReserveSize(Country.UA)).append('\n');

		if (center != null) {
			double ruFront = minDistanceToCenter(level, Country.RU, center);
			double uaFront = minDistanceToCenter(level, Country.UA, center);
			builder.append("frontDistToCenter RU=")
					.append(fmt(ruFront))
					.append(" UA=")
					.append(fmt(uaFront))
					.append('\n');
		}

		double armies = nearestArmyDistance(level, Country.RU, Country.UA);
		builder.append("nearestArmyDist=").append(fmt(armies))
				.append(" initial=").append(fmt(initialFrontDistance))
				.append(" best=").append(fmt(bestFrontDistance < Double.MAX_VALUE ? bestFrontDistance : -1))
				.append('\n');
		builder.append("livingSearchRadius=").append(fmt(FighterTargeting.getLivingSearchRadius())).append('\n');

		FighterTargeting.MarchSnapshot snap = FighterTargeting.collectMarchSnapshot(level);
		builder.append("withLivingTarget=").append(snap.withLivingTarget).append('\n');
		builder.append("withCoreTarget=").append(snap.withCoreAttackTarget).append('\n');
		builder.append("withRally=").append(snap.withRally).append('\n');
		builder.append("navigating=").append(snap.navigating)
				.append(" withoutNav=").append(snap.withoutNavigation).append('\n');
		return builder.toString();
	}

	private static double minDistanceToCenter(ServerLevel level, Country country, BlockPos center) {
		double best = -1.0;
		double cx = center.getX() + 0.5;
		double cz = center.getZ() + 0.5;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}
			if (FighterFactory.getCountry(fighter) != country) {
				continue;
			}
			double dx = fighter.getX() - cx;
			double dz = fighter.getZ() - cz;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (best < 0 || dist < best) {
				best = dist;
			}
		}
		return best;
	}

	/**
	 * Nearest RU–UA distance with sampling when armies are large (test-only, not production AI).
	 */
	private static double nearestArmyDistance(ServerLevel level, Country a, Country b) {
		List<ArenaFighterEntity> sideA = new ArrayList<>();
		List<ArenaFighterEntity> sideB = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !FighterFactory.isArenaFighter(fighter) || !fighter.isAlive()) {
				continue;
			}
			Country country = FighterFactory.getCountry(fighter);
			if (country == a) {
				sideA.add(fighter);
			} else if (country == b) {
				sideB.add(fighter);
			}
		}
		if (sideA.isEmpty() || sideB.isEmpty()) {
			return -1.0;
		}

		int strideA = Math.max(1, sideA.size() / 25);
		int strideB = Math.max(1, sideB.size() / 25);
		double best = Double.MAX_VALUE;
		for (int i = 0; i < sideA.size(); i += strideA) {
			ArenaFighterEntity fa = sideA.get(i);
			for (int j = 0; j < sideB.size(); j += strideB) {
				double d = fa.distanceToSqr(sideB.get(j));
				if (d < best) {
					best = d;
				}
			}
		}
		return Math.sqrt(best);
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

	private static String fmt(double value) {
		if (value < 0) {
			return "n/a";
		}
		return String.format(Locale.US, "%.1f", value);
	}

	private enum Stage {
		IDLE,
		SETUP,
		WAIT_WAVE,
		LEFT_SPAWN,
		CLOSING,
		LIVING_TARGET,
		MELEE,
		DONE
	}
}
