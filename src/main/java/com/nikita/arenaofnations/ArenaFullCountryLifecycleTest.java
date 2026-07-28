package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic end-to-end country lifecycle test (RU / UA / KZ).
 * Uses real fighter combat, core combat, rescue, elimination and winner logic.
 */
public final class ArenaFullCountryLifecycleTest {
	private static final ArenaFullCountryLifecycleTest INSTANCE = new ArenaFullCountryLifecycleTest();

	private static final int BATTLE_SECONDS = 600;
	private static final int STAGE2_TIMEOUT = 40 * 20;
	private static final int STAGE3_TIMEOUT = 160 * 20;
	private static final int STAGE4_WAVE_TIMEOUT = 8 * 20;
	private static final int STAGE5_DEFENDER_TIMEOUT = 40 * 20;
	private static final int STAGE5_CORE_TIMEOUT = 120 * 20;
	private static final int STAGE7_DEFENDER_TIMEOUT = 40 * 20;
	private static final int STAGE7_CORE_TIMEOUT = 160 * 20;

	private boolean running;
	private Stage stage = Stage.IDLE;
	private UUID playerId;
	private Vec3 origin = Vec3.ZERO;
	private String levelKey = "";
	private int stageTicks;
	private int elapsedTicks;
	private String lastFailure = "";
	private final EnumMap<Stage, Boolean> stagePass = new EnumMap<>(Stage.class);

	private float uaHpAtStage3Start = 200.0F;
	private Map<BlockPos, BlockState> uaBlocksBeforeAttack = Map.of();
	private int uaItemsBeforeAttack;
	private long overlaySequenceAtStart;
	private boolean sawRescueInOverlay;
	private boolean sawEliminatedInOverlay;
	private boolean giftedUaRescue;
	private boolean stage5CoreWait;
	private boolean postRescueSettle;
	private int postRescueSettleTicks;

	private ArenaFullCountryLifecycleTest() {
	}

	public static ArenaFullCountryLifecycleTest get() {
		return INSTANCE;
	}

	public void cancel() {
		if (!running) {
			return;
		}
		running = false;
		lastFailure = "cancelled";
		stage = Stage.IDLE;
	}

	public boolean isRunning() {
		return running;
	}

	public String statusReport(MinecraftServer server) {
		ServerLevel level = resolveLevel(server);
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		StringBuilder builder = new StringBuilder("Lifecycle status:\n");
		builder.append("running=").append(running).append('\n');
		builder.append("stage=").append(stage).append('\n');
		builder.append("elapsed_ticks=").append(elapsedTicks).append('\n');
		builder.append("stage_ticks=").append(stageTicks).append('\n');
		builder.append("match=").append(match.getState()).append('\n');
		builder.append("winner=").append(match.getLastRoundWinner()).append('\n');
		builder.append("overlay_sequence=").append(overlay.snapshotSequence()).append('\n');
		builder.append("last_failure=").append(lastFailure.isEmpty() ? "(none)" : lastFailure).append('\n');
		for (Country country : List.of(Country.RU, Country.UA, Country.KZ)) {
			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			builder.append(country.getCode())
					.append(": living=")
					.append(level == null ? -1 : match.countLivingFightersUncached(level, country))
					.append(" reserve=")
					.append(match.getReserveSize(country))
					.append(" core=")
					.append(Math.round(core.getCurrentHealth()))
					.append('/')
					.append(Math.round(core.getMaxHealth()))
					.append(" protected=")
					.append(level != null && ArenaCoreManager.get().isCoreProtected(level, country))
					.append(" rescue=")
					.append(ArenaCoreRescueManager.get().getRescueRemainingSeconds(server, country))
					.append("s eliminated=")
					.append(ArenaCoreRescueManager.get().isEliminated(country))
					.append('\n');
		}
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		builder.append("RU coreTarget=")
				.append(ru == null ? "n/a" : ArenaCoreCombatManager.get().getCoreTarget(ru.getUUID()))
				.append('\n');
		return builder.toString();
	}

	public String start(MinecraftServer server, ServerLevel level, Vec3 origin, UUID playerId) {
		resetInternal();
		this.playerId = playerId;
		this.origin = origin;
		this.levelKey = level.dimension().location().toString();
		this.stage = Stage.SETUP;
		this.overlaySequenceAtStart = ArenaOverlayStateService.get().snapshotSequence();

		ArenaMatchManager match = ArenaMatchManager.get();
		// reset() calls cancelDeferred(); keep running=false until setup completes,
		// otherwise reset would immediately cancel this lifecycle test.
		match.reset(server);
		match.setEconomyTestBattle(true);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(true);

		match.prepareTestBattle(server, level, origin, Country.RU, Country.UA, Country.KZ);
		match.spawnTestFightersOnField(server, level, Country.RU, 1);
		match.spawnTestFightersOnField(server, level, Country.UA, 1);
		match.spawnTestFightersOnField(server, level, Country.KZ, 1);

		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, level);
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		placeDeterministic(fightLevel, center);

		match.startPreparedTestBattle(server);
		match.setBattleRemainingSecondsForTest(BATTLE_SECONDS);
		ArenaCoreManager.get().setProtectionAnnouncementsSuppressed(false);
		ArenaCoreManager.get().clearProtectionStateTracking();
		ArenaRoundHudSync.pushNow(server);

		this.running = true;
		enterStage(Stage.STAGE1_PROTECTED);
		return "full_country_lifecycle started (RU/UA/KZ). Track with /arena_lifecycle_status";
	}

	public void tick(MinecraftServer server) {
		if (!running) {
			return;
		}
		elapsedTicks++;
		stageTicks++;
		ServerLevel level = resolveLevel(server);
		if (level == null) {
			fail(server, "fight level missing");
			return;
		}

		try {
			switch (stage) {
				case STAGE1_PROTECTED -> tickStage1(server, level);
				case STAGE2_LAST_DEFENDER -> tickStage2(server, level);
				case STAGE3_CORE_ATTACK -> tickStage3(server, level);
				case STAGE4_RESCUE -> tickStage4(server, level);
				case STAGE5_ELIMINATION -> tickStage5(server, level);
				case STAGE6_ROUND_CONTINUES -> tickStage6(server, level);
				case STAGE7_WINNER -> tickStage7(server, level);
				default -> {
				}
			}
		} catch (Exception e) {
			ArenaOfNations.LOGGER.error("full_country_lifecycle failed", e);
			fail(server, "exception: " + e.getMessage());
		}
	}

	private void tickStage1(MinecraftServer server, ServerLevel level) {
		if (stageTicks < 2) {
			return;
		}
		List<String> errors = new ArrayList<>();
		ArenaMatchManager match = ArenaMatchManager.get();
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (uaLive != 1) {
			errors.add("UA living=" + uaLive);
		}
		if (!ArenaCoreManager.get().isCoreProtected(level, Country.UA)) {
			errors.add("UA protected=false");
		}
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		float before = uaCore.getCurrentHealth();
		float after = ArenaCoreManager.get().damageFromFighter(server, level, Country.UA, Country.RU, 50.0F);
		if (Math.abs(after - before) > 0.01F) {
			errors.add("protected core took damage " + before + "→" + after);
		}
		if (Math.abs(uaCore.getCurrentHealth() - uaCore.getMaxHealth()) > 0.01F) {
			errors.add("UA core HP not full");
		}
		ArenaRoundHudSync.pushNow(server);
		String overlayStatus = overlayCountryStatus(Country.UA);
		if (!"PROTECTED".equals(overlayStatus)) {
			errors.add("overlay UA status=" + overlayStatus);
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE1: " + String.join("; ", errors));
			return;
		}
		passAndEnter(server, Stage.STAGE1_PROTECTED, Stage.STAGE2_LAST_DEFENDER);
	}

	private void tickStage2(MinecraftServer server, ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (uaLive > 0) {
			if (stageTicks > STAGE2_TIMEOUT) {
				fail(server, "STAGE2 timeout: UA living still " + uaLive);
			}
			return;
		}
		List<String> errors = new ArrayList<>();
		if (ArenaCoreManager.get().isCoreProtected(level, Country.UA)) {
			errors.add("UA still protected");
		}
		if (ArenaCoreRescueManager.get().isEliminated(Country.UA)) {
			errors.add("UA eliminated too early");
		}
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		if (ru != null && ru.getTarget() != null && !ru.getTarget().isAlive()) {
			errors.add("RU sticky dead target");
		}
		ArenaRoundHudSync.pushNow(server);
		String overlayStatus = overlayCountryStatus(Country.UA);
		if (!"VULNERABLE".equals(overlayStatus) && !"RESCUE".equals(overlayStatus)) {
			errors.add("overlay UA status=" + overlayStatus);
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE2: " + String.join("; ", errors));
			return;
		}
		// Prepare stage 3 diagnostics: place RU at UA approach and snapshot blocks.
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.UA);
		ArenaFighterEntity ruFighter = findAlive(level, Country.RU);
		if (ruFighter != null) {
			ruFighter.teleportTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
			ruFighter.setNoAi(false);
			ruFighter.setTarget(null);
		}
		int uaSlot = match.getBaseSlot(Country.UA);
		uaBlocksBeforeAttack = snapshotBaseBlocks(level, center, uaSlot);
		uaItemsBeforeAttack = countNearbyItems(level, ArenaCountryBaseLayout.corePosition(center, uaSlot), 18.0D);
		uaHpAtStage3Start = ArenaCoreManager.get().getState(Country.UA).getCurrentHealth();
		ArenaRoundHudSync.pushNow(server);
		passAndEnter(server, Stage.STAGE2_LAST_DEFENDER, Stage.STAGE3_CORE_ATTACK);
	}

	private void tickStage3(MinecraftServer server, ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		if (ru == null) {
			fail(server, "STAGE3: RU fighter missing");
			return;
		}
		Country coreTarget = ArenaCoreCombatManager.get().getCoreTarget(ru.getUUID());
		boolean damaged = uaCore.getCurrentHealth() < uaHpAtStage3Start - 0.05F;
		boolean destroyed = uaCore.getCurrentHealth() <= 0.0F;
		boolean rescuing = ArenaCoreRescueManager.get().isRescuing(Country.UA);

		if (!damaged && !destroyed && !rescuing) {
			if (stageTicks > STAGE3_TIMEOUT) {
				fail(server, "STAGE3 timeout: no core damage; coreTarget=" + coreTarget
						+ " HP=" + uaCore.getCurrentHealth());
			}
			return;
		}

		if (!destroyed && !rescuing) {
			// Wait until core is fully destroyed / rescue starts.
			if (stageTicks > STAGE3_TIMEOUT) {
				fail(server, "STAGE3 timeout: partial damage only HP=" + uaCore.getCurrentHealth());
			}
			return;
		}

		List<String> errors = new ArrayList<>();
		if (coreTarget != Country.UA && ArenaCoreManager.get().getCoreDamageDealt(Country.RU) <= 0.0D) {
			errors.add("RU never targeted/damaged UA core");
		}
		if (ArenaCoreManager.get().getCoreDamageDealt(Country.RU) <= 0.0D) {
			errors.add("RU coreDamageDealt=0");
		}
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.UA);
		if (!ArenaLayoutPathfinder.hasNavigationPathToTarget(level, ru.blockPosition(), approach)
				&& !ArenaCoreCombatManager.get().isInCoreAttackRange(ru, center, Country.UA)
				&& ArenaCoreManager.get().getCoreDamageDealt(Country.RU) <= 0.0D) {
			errors.add("no path to UA approach and no core damage");
		}
		int changed = countChangedBlocks(uaBlocksBeforeAttack, level);
		if (changed > 0) {
			errors.add("changedBlocks=" + changed);
		}
		int uaSlot = match.getBaseSlot(Country.UA);
		int itemsNow = countNearbyItems(level, ArenaCountryBaseLayout.corePosition(center, uaSlot), 18.0D);
		if (itemsNow > uaItemsBeforeAttack) {
			errors.add("newItemEntities=" + (itemsNow - uaItemsBeforeAttack));
		}
		if (!ArenaCoreRescueManager.get().isRescuing(Country.UA) && uaCore.getCurrentHealth() <= 0.0F) {
			ArenaCoreRescueManager.get().tick(server);
		}
		if (!ArenaCoreRescueManager.get().isRescuing(Country.UA)) {
			errors.add("rescue not started after core HP=0");
		}
		if (ArenaCoreRescueManager.get().isEliminated(Country.UA)) {
			errors.add("UA eliminated during rescue window");
		}
		ArenaRoundHudSync.pushNow(server);
		String overlayStatus = overlayCountryStatus(Country.UA);
		if ("RESCUE".equals(overlayStatus)) {
			sawRescueInOverlay = true;
		} else {
			errors.add("overlay UA status=" + overlayStatus + " (expected RESCUE)");
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE3: " + String.join("; ", errors));
			return;
		}
		passAndEnter(server, Stage.STAGE3_CORE_ATTACK, Stage.STAGE4_RESCUE);
		giftedUaRescue = false;
		postRescueSettle = false;
		postRescueSettleTicks = 0;
	}

	private void tickStage4(MinecraftServer server, ServerLevel level) {
		if (!giftedUaRescue) {
			if (!ArenaCoreRescueManager.get().isRescuing(Country.UA)) {
				fail(server, "STAGE4: rescue ended before gift");
				return;
			}
			ArenaMatchManager.get().handleGift(server, level, origin, Country.UA, 1);
			giftedUaRescue = true;
			ArenaRoundHudSync.pushNow(server);
			stageTicks = 0;
			return;
		}

		ArenaMatchManager match = ArenaMatchManager.get();
		int uaLive = match.countLivingFightersUncached(level, Country.UA);
		if (uaLive < 1) {
			if (stageTicks > STAGE4_WAVE_TIMEOUT) {
				fail(server, "STAGE4: no UA fighter after rescue gift/wave");
			}
			return;
		}
		if (!postRescueSettle) {
			postRescueSettleTicks++;
			if (postRescueSettleTicks < 5) {
				return;
			}
			postRescueSettle = true;
		}

		List<String> errors = new ArrayList<>();
		ArenaCoreState uaCore = ArenaCoreManager.get().getState(Country.UA);
		float expected = uaCore.getMaxHealth() * (ArenaConfig.get().getCoreRescueHealthPercent() / 100.0F);
		if (Math.abs(uaCore.getCurrentHealth() - expected) > 0.2F) {
			errors.add("UA HP=" + uaCore.getCurrentHealth() + " expected~" + expected);
		}
		if (ArenaCoreRescueManager.get().isRescuing(Country.UA)) {
			errors.add("rescue still active");
		}
		if (ArenaCoreRescueManager.get().isEliminated(Country.UA)) {
			errors.add("UA eliminated after rescue gift");
		}
		if (!ArenaCoreManager.get().isCoreProtected(level, Country.UA)) {
			errors.add("UA not protected after new fighter");
		}
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		if (ru != null && ArenaCoreCombatManager.get().getCoreTarget(ru.getUUID()) == Country.UA) {
			errors.add("RU still has UA core target after rescue");
		}
		ArenaRoundHudSync.pushNow(server);
		String overlayStatus = overlayCountryStatus(Country.UA);
		if (!"PROTECTED".equals(overlayStatus)) {
			errors.add("overlay UA status=" + overlayStatus);
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE4: " + String.join("; ", errors));
			return;
		}

		// Prepare stage 5: weaken new UA defender and place RU nearby.
		ArenaFighterEntity ua = findAlive(level, Country.UA);
		if (ua != null) {
			ua.setHealth(1.0F);
		}
		BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
		if (ru != null && ua != null) {
			ru.teleportTo(ua.getX() + 1.2D, ua.getY(), ua.getZ());
			ru.setTarget(ua);
			ru.setNoAi(false);
		} else if (ru != null) {
			BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.UA);
			ru.teleportTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
		}
		stage5CoreWait = false;
		passAndEnter(server, Stage.STAGE4_RESCUE, Stage.STAGE5_ELIMINATION);
	}

	private void tickStage5(MinecraftServer server, ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		int uaLive = match.countLivingFightersUncached(level, Country.UA);

		if (!stage5CoreWait) {
			if (uaLive > 0) {
				if (stageTicks > STAGE5_DEFENDER_TIMEOUT) {
					fail(server, "STAGE5 timeout waiting UA defender death");
				}
				return;
			}
			// Place RU at approach for second core destroy; no gift this time.
			BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
			BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.UA);
			ArenaFighterEntity ru = findAlive(level, Country.RU);
			if (ru != null) {
				ru.teleportTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
				ru.setTarget(null);
				ru.setNoAi(false);
			}
			stage5CoreWait = true;
			stageTicks = 0;
			return;
		}

		boolean eliminated = ArenaCoreRescueManager.get().isEliminated(Country.UA);
		boolean rescuing = ArenaCoreRescueManager.get().isRescuing(Country.UA);
		float hp = ArenaCoreManager.get().getState(Country.UA).getCurrentHealth();

		if (!eliminated) {
			int rescueWait = ArenaConfig.get().getCoreRescueSeconds() * 20 + 40;
			int timeout = STAGE5_CORE_TIMEOUT + rescueWait;
			if (stageTicks > timeout) {
				fail(server, "STAGE5 timeout: HP=" + hp + " rescue=" + rescuing + " eliminated=false");
			}
			return;
		}

		List<String> errors = new ArrayList<>();
		if (match.getReserveSize(Country.UA) != 0) {
			errors.add("UA reserve=" + match.getReserveSize(Country.UA));
		}
		if (match.countLivingFightersUncached(level, Country.UA) != 0) {
			errors.add("UA living after elim");
		}
		if (match.getActiveCountries().contains(Country.UA)) {
			errors.add("UA still active");
		}
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		if (ru != null && ArenaCoreCombatManager.get().getCoreTarget(ru.getUUID()) == Country.UA) {
			errors.add("UA core still targeted");
		}
		// Gift after elim must not restore country fighters onto field.
		int livingBeforeGift = match.countLivingFightersUncached(level, Country.UA);
		match.handleGift(server, level, origin, Country.UA, 3);
		int livingAfterGift = match.countLivingFightersUncached(level, Country.UA);
		if (livingAfterGift > livingBeforeGift) {
			errors.add("UA reinforcements after elimination");
		}
		ArenaRoundHudSync.pushNow(server);
		String overlayStatus = overlayCountryStatus(Country.UA);
		if ("ELIMINATED".equals(overlayStatus)) {
			sawEliminatedInOverlay = true;
		} else {
			errors.add("overlay UA status=" + overlayStatus);
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE5: " + String.join("; ", errors));
			return;
		}
		passAndEnter(server, Stage.STAGE5_ELIMINATION, Stage.STAGE6_ROUND_CONTINUES);
	}

	private void tickStage6(MinecraftServer server, ServerLevel level) {
		List<String> errors = new ArrayList<>();
		ArenaMatchManager match = ArenaMatchManager.get();
		if (match.getState() != ArenaMatchState.BATTLE) {
			errors.add("match=" + match.getState());
		}
		int aliveCountries = 0;
		for (Country country : match.getActiveCountries()) {
			if (!ArenaCoreRescueManager.get().isEliminated(country)) {
				aliveCountries++;
			}
		}
		if (aliveCountries != 2) {
			errors.add("aliveCountries=" + aliveCountries);
		}
		if (!match.getActiveCountries().contains(Country.RU) || !match.getActiveCountries().contains(Country.KZ)) {
			errors.add("RU/KZ not both active");
		}
		if (ArenaCoreRescueManager.get().isEliminated(Country.RU)
				|| ArenaCoreRescueManager.get().isEliminated(Country.KZ)) {
			errors.add("RU or KZ eliminated early");
		}
		ArenaRoundHudSync.pushNow(server);
		if (!errors.isEmpty()) {
			fail(server, "STAGE6: " + String.join("; ", errors));
			return;
		}

		// Start stage 7: wake KZ, weaken, place RU nearby.
		ArenaFighterEntity kz = findAlive(level, Country.KZ);
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		if (kz != null) {
			kz.setNoAi(false);
			kz.setHealth(1.0F);
		}
		if (ru != null && kz != null) {
			ru.teleportTo(kz.getX() + 1.2D, kz.getY(), kz.getZ());
			ru.setTarget(kz);
			ru.setNoAi(false);
		}
		passAndEnter(server, Stage.STAGE6_ROUND_CONTINUES, Stage.STAGE7_WINNER);
	}

	private void tickStage7(MinecraftServer server, ServerLevel level) {
		ArenaMatchManager match = ArenaMatchManager.get();
		boolean kzEliminated = ArenaCoreRescueManager.get().isEliminated(Country.KZ);
		boolean battleEnded = match.getState() == ArenaMatchState.BREAK
				|| match.getState() == ArenaMatchState.IDLE
				|| match.getLastRoundWinner() == Country.RU;

		if (!kzEliminated || !battleEnded) {
			int kzLive = match.countLivingFightersUncached(level, Country.KZ);
			if (kzLive == 0 && !kzEliminated) {
				ArenaFighterEntity ru = findAlive(level, Country.RU);
				BlockPos center = BlockPos.containing(match.getMatchCenter().x, match.getMatchCenter().y, match.getMatchCenter().z);
				BlockPos approach = ArenaPositions.resolveCoreApproachPosition(level, center, Country.KZ);
				if (ru != null) {
					ru.teleportTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
					ru.setTarget(null);
					ru.setNoAi(false);
				}
			}
			int timeout = STAGE7_DEFENDER_TIMEOUT + STAGE7_CORE_TIMEOUT
					+ ArenaConfig.get().getCoreRescueSeconds() * 20 + 40;
			if (stageTicks > timeout) {
				fail(server, "STAGE7 timeout: kzElim=" + kzEliminated
						+ " winner=" + match.getLastRoundWinner()
						+ " state=" + match.getState());
			}
			return;
		}

		List<String> errors = new ArrayList<>();
		if (match.getLastRoundWinner() != Country.RU) {
			errors.add("winner=" + match.getLastRoundWinner());
		}
		if (ArenaCoreRescueManager.get().isEliminated(Country.RU)) {
			errors.add("RU eliminated");
		}
		ArenaRoundHudSync.pushNow(server);
		long seq = ArenaOverlayStateService.get().snapshotSequence();
		if (seq <= overlaySequenceAtStart) {
			errors.add("overlay sequence not advanced");
		}
		String phase = overlayPhase();
		if (!"BREAK".equals(phase) && !"IDLE".equals(phase) && !"BATTLE".equals(phase)) {
			errors.add("overlay phase=" + phase);
		}
		if (!errors.isEmpty()) {
			fail(server, "STAGE7: " + String.join("; ", errors));
			return;
		}
		stagePass.put(Stage.STAGE7_WINNER, true);
		finishSuccess(server);
	}

	private void placeDeterministic(ServerLevel level, BlockPos center) {
		ArenaFighterEntity ru = findAlive(level, Country.RU);
		ArenaFighterEntity ua = findAlive(level, Country.UA);
		ArenaFighterEntity kz = findAlive(level, Country.KZ);

		double duelX = center.getX() + 0.5D;
		double duelY = center.getY() + 1.0D;
		double duelZ = center.getZ() + 0.5D;
		if (ua != null) {
			ua.teleportTo(duelX + 1.5D, duelY, duelZ);
			ua.setHealth(1.0F);
			ua.setNoAi(false);
		}
		if (ru != null) {
			ru.teleportTo(duelX - 1.5D, duelY, duelZ);
			ru.setNoAi(false);
			if (ua != null) {
				ru.setTarget(ua);
			}
		}
		if (kz != null) {
			BlockPos kzSpawn = ArenaCountryBaseLayout.spawnZoneCenter(center, ArenaMatchManager.get().getBaseSlot(Country.KZ));
			kz.teleportTo(kzSpawn.getX() + 0.5D, kzSpawn.getY() + 1.0D, kzSpawn.getZ() + 0.5D);
			kz.setNoAi(true);
			kz.setTarget(null);
		}
	}

	private void passAndEnter(MinecraftServer server, Stage completed, Stage next) {
		stagePass.put(completed, true);
		enterStage(next);
		ArenaRoundHudSync.pushNow(server);
	}

	private void enterStage(Stage next) {
		stage = next;
		stageTicks = 0;
	}

	private void fail(MinecraftServer server, String reason) {
		lastFailure = reason;
		running = false;
		String report = formatReport(false);
		notifyPlayer(server, report);
		ArenaTestScenarioCommands.onLifecycleFinished();
	}

	private void finishSuccess(MinecraftServer server) {
		running = false;
		lastFailure = "";
		String report = formatReport(true);
		notifyPlayer(server, report);
		ArenaTestScenarioCommands.onLifecycleFinished();
	}

	private String formatReport(boolean pass) {
		StringBuilder builder = new StringBuilder();
		if (pass) {
			builder.append("FULL COUNTRY LIFECYCLE: PASS\n\nStages:\n");
		} else {
			builder.append("FULL COUNTRY LIFECYCLE: FAILED\n");
			builder.append("stage=").append(stage).append('\n');
			builder.append("reason=").append(lastFailure).append("\n\nStages:\n");
		}
		appendStageLine(builder, 1, Stage.STAGE1_PROTECTED);
		appendStageLine(builder, 2, Stage.STAGE2_LAST_DEFENDER);
		appendStageLine(builder, 3, Stage.STAGE3_CORE_ATTACK);
		appendStageLine(builder, 4, Stage.STAGE4_RESCUE);
		appendStageLine(builder, 5, Stage.STAGE5_ELIMINATION);
		appendStageLine(builder, 6, Stage.STAGE6_ROUND_CONTINUES);
		appendStageLine(builder, 7, Stage.STAGE7_WINNER);
		builder.append("\noverlayRescueSeen=").append(sawRescueInOverlay);
		builder.append("\noverlayEliminatedSeen=").append(sawEliminatedInOverlay);
		builder.append("\nelapsedTicks=").append(elapsedTicks);
		return builder.toString();
	}

	private void appendStageLine(StringBuilder builder, int number, Stage s) {
		String name = switch (s) {
			case STAGE1_PROTECTED -> "PROTECTED";
			case STAGE2_LAST_DEFENDER -> "LAST DEFENDER";
			case STAGE3_CORE_ATTACK -> "CORE ATTACK";
			case STAGE4_RESCUE -> "RESCUE";
			case STAGE5_ELIMINATION -> "ELIMINATION";
			case STAGE6_ROUND_CONTINUES -> "ROUND CONTINUES";
			case STAGE7_WINNER -> "WINNER";
			default -> s.name();
		};
		String mark;
		if (Boolean.TRUE.equals(stagePass.get(s))) {
			mark = "PASS";
		} else if (stage == s && !lastFailure.isEmpty()) {
			mark = "FAIL";
		} else {
			mark = "—";
		}
		builder.append(number).append(' ').append(name).append(' ').append(mark).append('\n');
	}

	private void notifyPlayer(MinecraftServer server, String report) {
		Component message = Component.literal("Сценарий: full_country_lifecycle\n" + report);
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player != null) {
			player.sendSystemMessage(message);
		} else {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
	}

	private void resetInternal() {
		running = false;
		stage = Stage.IDLE;
		stageTicks = 0;
		elapsedTicks = 0;
		lastFailure = "";
		stagePass.clear();
		uaBlocksBeforeAttack = Map.of();
		uaItemsBeforeAttack = 0;
		sawRescueInOverlay = false;
		sawEliminatedInOverlay = false;
		giftedUaRescue = false;
		stage5CoreWait = false;
		postRescueSettle = false;
		postRescueSettleTicks = 0;
	}

	private ServerLevel resolveLevel(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(levelKey)) {
				return ArenaSpawns.resolveFightLevel(server, level);
			}
		}
		return ArenaSpawns.resolveFightLevel(server, server.overworld());
	}

	private static ArenaFighterEntity findAlive(ServerLevel level, Country country) {
		if (level == null) {
			return null;
		}
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ArenaFighterEntity fighter
					&& FighterFactory.isArenaFighter(fighter)
					&& fighter.isAlive()
					&& fighter.getArenaCountry() == country) {
				return fighter;
			}
		}
		return null;
	}

	private static String overlayCountryStatus(Country country) {
		try {
			JsonObject root = JsonParser.parseString(ArenaOverlayStateService.get().snapshotJson()).getAsJsonObject();
			JsonArray arr = root.getAsJsonArray("countries");
			if (arr == null) {
				return "MISSING";
			}
			for (JsonElement element : arr) {
				JsonObject item = element.getAsJsonObject();
				if (country.getId().equals(item.get("id").getAsString())) {
					return item.has("status") ? item.get("status").getAsString() : "MISSING";
				}
			}
			return "MISSING";
		} catch (Exception e) {
			return "ERROR";
		}
	}

	private static String overlayPhase() {
		try {
			JsonObject root = JsonParser.parseString(ArenaOverlayStateService.get().snapshotJson()).getAsJsonObject();
			return root.has("phase") ? root.get("phase").getAsString() : "MISSING";
		} catch (Exception e) {
			return "ERROR";
		}
	}

	private static Map<BlockPos, BlockState> snapshotBaseBlocks(ServerLevel level, BlockPos arenaCenter, int slot) {
		BlockPos core = ArenaCountryBaseLayout.corePosition(arenaCenter, slot);
		var outward = ArenaCountryBaseLayout.outwardDirection(slot);
		var side = outward.getClockWise();
		BlockPos footing = core.below();
		Map<BlockPos, BlockState> map = new LinkedHashMap<>();
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

	private static int countChangedBlocks(Map<BlockPos, BlockState> before, ServerLevel level) {
		int changed = 0;
		for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
			if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
				changed++;
			}
		}
		return changed;
	}

	private static int countNearbyItems(ServerLevel level, BlockPos center, double radius) {
		double r2 = radius * radius;
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ItemEntity) {
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

	public enum Stage {
		IDLE,
		SETUP,
		STAGE1_PROTECTED,
		STAGE2_LAST_DEFENDER,
		STAGE3_CORE_ATTACK,
		STAGE4_RESCUE,
		STAGE5_ELIMINATION,
		STAGE6_ROUND_CONTINUES,
		STAGE7_WINNER
	}
}
