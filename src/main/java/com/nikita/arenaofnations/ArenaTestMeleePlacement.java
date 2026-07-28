package com.nikita.arenaofnations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Test-only initial placement for melee scenarios. Does not affect gift spawn, combat AI, or formations.
 */
final class ArenaTestMeleePlacement {
	private static final double CONTACT_LINE_OFFSET_X = 4.0D;
	private static final double CONTACT_Z_SPACING = 2.0D;
	private static final double MIN_ALLY_CENTER_DISTANCE = 1.6D;
	private static final double MIN_TEAM_LINE_DISTANCE = 7.0D;
	private static final double MAX_TEAM_LINE_DISTANCE = 9.0D;

	private static final double DENSITY_NEAR_X = 6.0D;
	private static final double DENSITY_FAR_X = 10.0D;
	private static final double DENSITY_Z_SPACING = 2.0D;
	private static final int DENSITY_ROWS = 4;
	private static final int DENSITY_COLUMNS = 5;

	private static final float RU_FACE_YAW = -90.0F;
	private static final float UA_FACE_YAW = 90.0F;

	private ArenaTestMeleePlacement() {
	}

	static String placeMeleeContact(MinecraftServer server, ServerLevel fightLevel, Vec3 origin, int perSide) {
		Vec3 center = ArenaSpawns.resolveMatchCenter(server, origin);
		BlockPos arenaCenter = BlockPos.containing(center.x, center.y, center.z);
		List<ArenaFighterEntity> ru = findFighters(fightLevel, Country.RU, perSide);
		List<ArenaFighterEntity> ua = findFighters(fightLevel, Country.UA, perSide);

		if (ru.size() < perSide || ua.size() < perSide) {
			String message = String.format(
					Locale.ROOT,
					"не хватает бойцов для melee_contact (RU=%d, UA=%d, ожидалось %d)",
					ru.size(),
					ua.size(),
					perSide);
			logAndBroadcast(server, message);
			return message;
		}

		double[] zOffsets = buildSymmetricOffsets(perSide, CONTACT_Z_SPACING);
		List<AABB> placed = new ArrayList<>();
		List<String> failures = new ArrayList<>();

		for (int index = 0; index < perSide; index++) {
			double z = center.z + zOffsets[index];
			double x = center.x - CONTACT_LINE_OFFSET_X;
			if (!tryPlaceFighter(fightLevel, arenaCenter, ru.get(index), x, z, RU_FACE_YAW, placed)) {
				failures.add("RU#" + index + " @ " + formatPos(x, z));
			}
		}
		for (int index = 0; index < perSide; index++) {
			double z = center.z + zOffsets[index];
			double x = center.x + CONTACT_LINE_OFFSET_X;
			if (!tryPlaceFighter(fightLevel, arenaCenter, ua.get(index), x, z, UA_FACE_YAW, placed)) {
				failures.add("UA#" + index + " @ " + formatPos(x, z));
			}
		}

		if (!failures.isEmpty()) {
			String message = "не удалось разместить бойцов melee_contact: " + String.join(", ", failures);
			logAndBroadcast(server, message);
			return message;
		}

		return validatePlacement(server, fightLevel, perSide, perSide, true);
	}

	static String placeMeleeDensity(MinecraftServer server, ServerLevel fightLevel, Vec3 origin, int perSide) {
		Vec3 center = ArenaSpawns.resolveMatchCenter(server, origin);
		BlockPos arenaCenter = BlockPos.containing(center.x, center.y, center.z);
		List<ArenaFighterEntity> ru = findFighters(fightLevel, Country.RU, perSide);
		List<ArenaFighterEntity> ua = findFighters(fightLevel, Country.UA, perSide);

		if (ru.size() < perSide || ua.size() < perSide) {
			String message = String.format(
					Locale.ROOT,
					"не хватает бойцов для melee_density (RU=%d, UA=%d, ожидалось %d)",
					ru.size(),
					ua.size(),
					perSide);
			logAndBroadcast(server, message);
			return message;
		}

		double[] zOffsets = buildSymmetricOffsets(DENSITY_COLUMNS, DENSITY_Z_SPACING);
		double[] rowOffsets = buildRowOffsets(DENSITY_NEAR_X, DENSITY_FAR_X, DENSITY_ROWS);
		List<double[]> ruSlots = buildGridSlots(center, rowOffsets, zOffsets, -1.0D);
		List<double[]> uaSlots = buildGridSlots(center, rowOffsets, zOffsets, 1.0D);
		if (ruSlots.size() < perSide || uaSlots.size() < perSide) {
			String message = "недостаточно слотов сетки для melee_density";
			logAndBroadcast(server, message);
			return message;
		}

		List<AABB> placed = new ArrayList<>();
		List<String> failures = new ArrayList<>();

		for (int index = 0; index < perSide; index++) {
			double[] slot = ruSlots.get(index);
			if (!tryPlaceFighter(fightLevel, arenaCenter, ru.get(index), slot[0], slot[1], RU_FACE_YAW, placed)) {
				failures.add("RU#" + index + " @ " + formatPos(slot[0], slot[1]));
			}
		}
		for (int index = 0; index < perSide; index++) {
			double[] slot = uaSlots.get(index);
			if (!tryPlaceFighter(fightLevel, arenaCenter, ua.get(index), slot[0], slot[1], UA_FACE_YAW, placed)) {
				failures.add("UA#" + index + " @ " + formatPos(slot[0], slot[1]));
			}
		}

		if (!failures.isEmpty()) {
			String message = "не удалось разместить бойцов melee_density: " + String.join(", ", failures);
			logAndBroadcast(server, message);
			return message;
		}

		return validatePlacement(server, fightLevel, perSide, perSide, false);
	}

	static String validateAfterBattleStart(
			MinecraftServer server,
			ServerLevel fightLevel,
			int ruCount,
			int uaCount,
			boolean checkTeamDistance) {
		if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE) {
			String message = "state != BATTLE (" + ArenaMatchManager.get().getState() + ")";
			logAndBroadcast(server, message);
			return message;
		}
		if (!ArenaCoreManager.get().isCoreProtected(fightLevel, Country.RU)) {
			String message = "isCoreProtected(RU)=false";
			logAndBroadcast(server, message);
			return message;
		}
		if (!ArenaCoreManager.get().isCoreProtected(fightLevel, Country.UA)) {
			String message = "isCoreProtected(UA)=false";
			logAndBroadcast(server, message);
			return message;
		}
		return validatePlacement(server, fightLevel, ruCount, uaCount, checkTeamDistance);
	}

	private static String validatePlacement(
			MinecraftServer server,
			ServerLevel fightLevel,
			int expectedRu,
			int expectedUa,
			boolean checkTeamDistance) {
		int ruLiving = ArenaMatchManager.get().countLivingFighters(fightLevel, Country.RU);
		int uaLiving = ArenaMatchManager.get().countLivingFighters(fightLevel, Country.UA);
		List<String> errors = new ArrayList<>();

		if (ruLiving != expectedRu) {
			errors.add("RU living=" + ruLiving + " (ожидалось " + expectedRu + ")");
		}
		if (uaLiving != expectedUa) {
			errors.add("UA living=" + uaLiving + " (ожидалось " + expectedUa + ")");
		}

		List<ArenaFighterEntity> fighters = collectAllFighters(fightLevel);
		for (ArenaFighterEntity fighter : fighters) {
			if (!(fighter.level() instanceof ServerLevel fighterLevel) || !ArenaSpawns.isFightLevel(fighterLevel)) {
				errors.add("боец вне fight level: " + fighter.getStringUUID());
			}
		}

		for (int left = 0; left < fighters.size(); left++) {
			ArenaFighterEntity a = fighters.get(left);
			for (int right = left + 1; right < fighters.size(); right++) {
				ArenaFighterEntity b = fighters.get(right);
				if (a.getBoundingBox().intersects(b.getBoundingBox())) {
					errors.add("пересечение AABB: "
							+ a.getArenaCountry()
							+ " и "
							+ b.getArenaCountry()
							+ " @ "
							+ formatPos(a.getX(), a.getZ()));
				}
				if (a.getArenaCountry() == b.getArenaCountry()) {
					double distance = a.position().distanceTo(b.position());
					if (distance + 0.05D < MIN_ALLY_CENTER_DISTANCE) {
						errors.add("слишком близко союзники "
								+ a.getArenaCountry()
								+ ": "
								+ String.format(Locale.ROOT, "%.2f", distance)
								+ " блоков");
					}
				}
			}
		}

		if (checkTeamDistance) {
			double minTeamDistance = computeMinTeamDistance(fighters);
			if (minTeamDistance < MIN_TEAM_LINE_DISTANCE || minTeamDistance > MAX_TEAM_LINE_DISTANCE) {
				errors.add("дистанция между командами "
						+ String.format(Locale.ROOT, "%.2f", minTeamDistance)
						+ " (ожидалось "
						+ MIN_TEAM_LINE_DISTANCE
						+ "–"
						+ MAX_TEAM_LINE_DISTANCE
						+ ")");
			}
		}

		if (errors.isEmpty()) {
			return null;
		}

		String message = String.join("; ", errors);
		logAndBroadcast(server, message);
		return message;
	}

	private static boolean tryPlaceFighter(
			ServerLevel level,
			BlockPos arenaCenter,
			ArenaFighterEntity fighter,
			double x,
			double z,
			float yRot,
			List<AABB> placedBoxes) {
		double[] zShifts = {0.0D, 0.35D, -0.35D, 0.7D, -0.7D, 1.0D, -1.0D, 1.35D, -1.35D};
		for (double zShift : zShifts) {
			double tryZ = z + zShift;
			double tryY = resolveFeetY(level, arenaCenter, x, tryZ);
			if (tryPlaceAt(level, fighter, x, tryY, tryZ, yRot, placedBoxes)) {
				return true;
			}
		}
		ArenaOfNations.LOGGER.error(
				"Test melee placement failed for {} {} at {} {}",
				fighter.getArenaCountry(),
				fighter.getArenaTier(),
				x,
				z);
		return false;
	}

	private static boolean tryPlaceAt(
			ServerLevel level,
			ArenaFighterEntity fighter,
			double x,
			double y,
			double z,
			float yRot,
			List<AABB> placedBoxes) {
		fighter.moveTo(x, y, z, yRot, 0.0F);
		AABB box = fighter.getBoundingBox();
		if (!level.noCollision(fighter, box)) {
			return false;
		}
		for (AABB placed : placedBoxes) {
			if (box.intersects(placed)) {
				return false;
			}
		}
		finalizeFighterForTestStart(fighter, yRot);
		placedBoxes.add(box);
		return true;
	}

	private static void finalizeFighterForTestStart(ArenaFighterEntity fighter, float yRot) {
		fighter.setDeltaMovement(Vec3.ZERO);
		fighter.getNavigation().stop();
		fighter.setTarget(null);
		ArenaCoreCombatManager.get().clearCoreTarget(fighter.getUUID());
		fighter.setYRot(yRot);
		fighter.setYBodyRot(yRot);
		fighter.setYHeadRot(yRot);
	}

	private static double resolveFeetY(ServerLevel level, BlockPos arenaCenter, double x, double z) {
		BlockPos column = BlockPos.containing(x, arenaCenter.getY(), z);
		for (int dy = -3; dy <= 5; dy++) {
			BlockPos feet = column.offset(0, dy, 0);
			if (ArenaPositions.isValidSpawn(level, arenaCenter, feet)) {
				return feet.getY();
			}
		}

		BlockPos surface = level.getHeightmapPos(
				Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(x, 0, z));
		return surface.getY();
	}

	private static double[] buildSymmetricOffsets(int count, double spacing) {
		double[] offsets = new double[count];
		double halfSpan = (count - 1) * 0.5D * spacing;
		for (int index = 0; index < count; index++) {
			offsets[index] = -halfSpan + index * spacing;
		}
		return offsets;
	}

	private static double[] buildRowOffsets(double minOffset, double maxOffset, int rows) {
		double[] offsets = new double[rows];
		if (rows == 1) {
			offsets[0] = (minOffset + maxOffset) * 0.5D;
			return offsets;
		}
		double step = (maxOffset - minOffset) / (rows - 1);
		for (int index = 0; index < rows; index++) {
			offsets[index] = minOffset + step * index;
		}
		return offsets;
	}

	private static List<double[]> buildGridSlots(Vec3 center, double[] rowOffsets, double[] zOffsets, double xSign) {
		List<double[]> slots = new ArrayList<>(rowOffsets.length * zOffsets.length);
		for (double rowOffset : rowOffsets) {
			for (double zOffset : zOffsets) {
				slots.add(new double[] {center.x + xSign * rowOffset, center.z + zOffset});
			}
		}
		return slots;
	}

	private static double computeMinTeamDistance(List<ArenaFighterEntity> fighters) {
		double minDistance = Double.MAX_VALUE;
		for (ArenaFighterEntity ru : fighters) {
			if (ru.getArenaCountry() != Country.RU) {
				continue;
			}
			for (ArenaFighterEntity ua : fighters) {
				if (ua.getArenaCountry() != Country.UA) {
					continue;
				}
				double distance = ru.position().distanceTo(ua.position());
				if (distance < minDistance) {
					minDistance = distance;
				}
			}
		}
		return minDistance == Double.MAX_VALUE ? 0.0D : minDistance;
	}

	private static List<ArenaFighterEntity> collectAllFighters(ServerLevel level) {
		List<ArenaFighterEntity> fighters = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ArenaFighterEntity fighter
					&& fighter.isAlive()
					&& FighterFactory.isArenaFighter(fighter)) {
				fighters.add(fighter);
			}
		}
		return fighters;
	}

	private static List<ArenaFighterEntity> findFighters(
			ServerLevel level,
			Country country,
			int maxCount) {
		List<ArenaFighterEntity> fighters = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter)
					|| !fighter.isAlive()
					|| !FighterFactory.isArenaFighter(fighter)
					|| fighter.getArenaCountry() != country) {
				continue;
			}
			fighters.add(fighter);
			if (fighters.size() >= maxCount) {
				break;
			}
		}
		return fighters;
	}

	private static void logAndBroadcast(MinecraftServer server, String message) {
		ArenaOfNations.LOGGER.error("Melee test placement validation failed: {}", message);
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("Melee test placement: " + message),
				false);
	}

	private static String formatPos(double x, double z) {
		return String.format(Locale.ROOT, "(%.2f, %.2f)", x, z);
	}
}
