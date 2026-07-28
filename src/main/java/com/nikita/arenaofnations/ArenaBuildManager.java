package com.nikita.arenaofnations;

import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Server-thread staged arena construction. Does not store Server/Level statically.
 */
public final class ArenaBuildManager {
	private static ActiveBuild active;

	private ArenaBuildManager() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaBuildManager::onServerTick);
		ArenaBuildCommands.register();
	}

	public static boolean isBuilding() {
		return active != null && !active.finished && !active.cancelled;
	}

	public static ActiveBuild getActive() {
		return active;
	}

	public static synchronized String startBuild(MinecraftServer server, ServerPlayer player, BlockPos standingBlock) {
		if (isBuilding()) {
			return "Строительство уже выполняется.";
		}

		ServerLevel level = player.serverLevel();
		if (level == null) {
			return "Измерение игрока недоступно.";
		}

		int minY = level.getMinBuildHeight();
		int maxY = level.getMaxBuildHeight() - 1;
		BlockPos center = new BlockPos(standingBlock.getX(), standingBlock.getY(), standingBlock.getZ());

		if (center.getY() - ArenaPositions.FOUNDATION_DEPTH < minY
				|| center.getY() + ArenaPositions.MAX_DECOR_HEIGHT > maxY) {
			return "Центр арены слишком близко к границам высоты мира.";
		}

		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup == null) {
			return "Не удалось получить хранилище настройки арены.";
		}

		boolean replacing = setup.isConfigured() || setup.isBuilt();
		ResourceLocation dimId = level.dimension().location();
		setup.configure(dimId, center);

		active = new ActiveBuild(
				level.dimension(),
				center,
				minY,
				maxY,
				player.getUUID(),
				new ArenaBuilder(center, minY, maxY));

		broadcastOps(server, Component.literal("Строительство большой арены началось."));
		if (replacing) {
			broadcastOps(server, Component.literal("Существующая настройка арены будет заменена новой."));
		}
		return null;
	}

	public static synchronized String cancelBuild(MinecraftServer server) {
		if (!isBuilding()) {
			return "Сейчас строительство не выполняется.";
		}
		active.cancelled = true;
		active.finished = true;
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null) {
			// keep configured=true; built remains false until a completed build
			setup.setDirty();
		}
		ActiveBuild cancelled = active;
		active = null;
		broadcastOps(server, Component.literal("Строительство остановлено. Уже изменённые блоки не восстановлены."));
		ArenaOfNations.LOGGER.info(
				"Arena build cancelled at stage {} ({}%)",
				cancelled.builder.getStage() == null ? "done" : cancelled.builder.getStage().label,
				cancelled.builder.getProgressPercent());
		return null;
	}

	private static void onServerTick(MinecraftServer server) {
		ActiveBuild job = active;
		if (job == null || job.finished || job.cancelled) {
			return;
		}

		ServerLevel level = server.getLevel(job.dimension);
		if (level == null) {
			ArenaOfNations.LOGGER.error("Arena build stopped: dimension {} is unavailable", job.dimension.location());
			broadcastOps(server, Component.literal("Строительство остановлено: измерение недоступно."));
			job.cancelled = true;
			job.finished = true;
			active = null;
			return;
		}

		int changed = job.builder.process(level, ArenaBuilder.BLOCKS_PER_TICK);
		job.opsDone += changed;

		int percent = job.builder.getProgressPercent();
		if (percent >= 25 && job.lastMilestone < 25) {
			job.lastMilestone = 25;
			broadcastOps(server, Component.literal("Строительство арены: 25% (" + job.builder.getStage().label + ")"));
		} else if (percent >= 50 && job.lastMilestone < 50) {
			job.lastMilestone = 50;
			broadcastOps(server, Component.literal("Строительство арены: 50% (" + job.builder.getStage().label + ")"));
		} else if (percent >= 75 && job.lastMilestone < 75) {
			job.lastMilestone = 75;
			broadcastOps(server, Component.literal("Строительство арены: 75% (" + job.builder.getStage().label + ")"));
		}

		if (job.builder.isComplete()) {
			finishSuccess(server, job);
		}
	}

	private static synchronized void finishSuccess(MinecraftServer server, ActiveBuild job) {
		if (active != job) {
			return;
		}
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null) {
			setup.markBuilt();
		}
		job.finished = true;
		active = null;

		BlockPos c = job.center;
		StringBuilder message = new StringBuilder();
		message.append("Большая арена построена.\n");
		message.append("Измерение: ").append(job.dimension.location()).append('\n');
		message.append("Центр: ").append(c.getX()).append(' ').append(c.getY()).append(' ').append(c.getZ()).append('\n');
		message.append("Радиус центра (рисунок): ").append(ArenaPositions.CENTER_PATTERN_RADIUS).append('\n');
		message.append("Радиус боевого прохода: ").append(ArenaPositions.COMBAT_WALKABLE_RADIUS).append('\n');
		message.append("Внешний радиус: ").append(ArenaPositions.OUTER_RADIUS).append('\n');
		message.append("Точки появления:\n");
		for (Country country : Country.values()) {
			BlockPos spawn = ArenaPositions.getCountryBase(c, country);
			message.append("- ")
					.append(country.getDisplayName())
					.append(": ")
					.append(spawn.getX()).append(' ')
					.append(spawn.getY()).append(' ')
					.append(spawn.getZ())
					.append('\n');
		}

		broadcastOps(server, Component.literal(message.toString().trim()));
		ArenaOfNations.LOGGER.info("Arena build completed at {}", c);
	}

	static void broadcastOps(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	public static final class ActiveBuild {
		final ResourceKey<Level> dimension;
		final BlockPos center;
		final int minY;
		final int maxY;
		final UUID initiator;
		final ArenaBuilder builder;
		long opsDone;
		int lastMilestone;
		boolean cancelled;
		boolean finished;

		ActiveBuild(ResourceKey<Level> dimension, BlockPos center, int minY, int maxY, UUID initiator, ArenaBuilder builder) {
			this.dimension = dimension;
			this.center = center;
			this.minY = minY;
			this.maxY = maxY;
			this.initiator = initiator;
			this.builder = builder;
		}

		public String stageLabel() {
			return builder.getStage() == null ? "завершено" : builder.getStage().label;
		}

		public int percent() {
			return builder.getProgressPercent();
		}

		public long estimatedTotal() {
			return builder.getEstimatedTotal();
		}
	}

	public static ServerLevel resolveArenaLevel(MinecraftServer server) {
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup == null || !setup.isConfigured() || !setup.isBuilt()) {
			return null;
		}
		ResourceLocation id = ResourceLocation.tryParse(setup.getDimension());
		if (id == null) {
			ArenaOfNations.LOGGER.error("Invalid arena dimension id: {}", setup.getDimension());
			return null;
		}
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
		ServerLevel level = server.getLevel(key);
		if (level == null) {
			ArenaOfNations.LOGGER.error("Configured arena dimension is missing: {}", id);
		}
		return level;
	}
}
