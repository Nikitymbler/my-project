package com.nikita.arenaofnations;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class ArenaLayoutCommands {
	private ArenaLayoutCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		ArenaCountryLayoutDebug.register();
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena_country_layout_status")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaLayoutCommands::layoutStatus));

		dispatcher.register(Commands.literal("arena_country_layout_validate")
				.requires(source -> source.hasPermission(2))
				.executes(ArenaLayoutCommands::layoutValidate));

		dispatcher.register(Commands.literal("arena_country_layout_debug")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("on").executes(ctx -> layoutDebug(ctx, true)))
				.then(Commands.literal("off").executes(ctx -> layoutDebug(ctx, false)))
				.then(Commands.argument("mode", StringArgumentType.word())
						.executes(ctx -> {
							String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
							if ("on".equals(mode) || "true".equals(mode) || "1".equals(mode)) {
								return layoutDebug(ctx, true);
							}
							if ("off".equals(mode) || "false".equals(mode) || "0".equals(mode)) {
								return layoutDebug(ctx, false);
							}
							ctx.getSource().sendFailure(Component.literal("Использование: /arena_country_layout_debug on|off"));
							return 0;
						})));
	}

	private static BlockPos resolveCenter(MinecraftServer server, CommandSourceStack source) {
		Vec3 centerVec = ArenaMatchManager.get().getMatchCenter();
		if (!centerVec.equals(Vec3.ZERO)) {
			return BlockPos.containing(centerVec.x, centerVec.y, centerVec.z);
		}
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (setup != null && setup.isConfigured()) {
			return setup.getCenter();
		}
		return source.getLevel().getSharedSpawnPos();
	}

	private static int layoutStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		ArenaMatchManager match = ArenaMatchManager.get();
		BlockPos center = resolveCenter(server, source);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, source.getLevel());

		StringBuilder builder = new StringBuilder("Arena layout v4 (")
				.append(ArenaCountryBaseLayout.BASE_SLOT_COUNT)
				.append(" physical slots)\n");
		builder.append("Radii: centerPattern=")
				.append(ArenaCountryBaseLayout.CENTER_PATTERN_RADIUS)
				.append(" walkable=")
				.append(ArenaCountryBaseLayout.COMBAT_WALKABLE_RADIUS)
				.append(" spawnZone=")
				.append(ArenaCountryBaseLayout.SPAWN_ZONE_RADIUS)
				.append(" core=")
				.append(ArenaCountryBaseLayout.CORE_RING_RADIUS)
				.append(" wall=")
				.append(ArenaCountryBaseLayout.OUTER_WALL_RADIUS)
				.append('\n');
		builder.append("Adjacent base distance≈")
				.append(String.format(Locale.ROOT, "%.1f", ArenaCountryBaseLayout.adjacentBaseCenterDistance()))
				.append(" blocks\n");
		builder.append("Active countries: ")
				.append(match.getActiveCountries().size())
				.append('/')
				.append(ArenaCountryBaseLayout.MAX_ACTIVE_COUNTRIES)
				.append('\n');

		java.util.LinkedHashSet<Country> listed = new java.util.LinkedHashSet<>(match.getCurrentRoundCountries());
		listed.addAll(match.getActiveCountries());

		if (listed.isEmpty()) {
			builder.append("Нет активных стран в раунде. Используйте /arena_country_layout_validate для всех 20 слотов.\n");
		}

		for (Country country : listed) {
			appendCountryLine(builder, match, fightLevel, center, country);
		}

		if (listed.isEmpty()) {
			for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
				appendSlotSummary(builder, fightLevel, center, slot, null);
			}
		}

		var errors = ArenaCountryBaseLayout.validateRoundLayout(center, match.getCountryBaseSlots());
		if (!errors.isEmpty()) {
			builder.append("Round warnings: ").append(String.join("; ", errors)).append('\n');
		}

		source.sendSuccess(() -> Component.literal(builder.toString()), false);
		return 1;
	}

	private static void appendCountryLine(
			StringBuilder builder,
			ArenaMatchManager match,
			ServerLevel fightLevel,
			BlockPos center,
			Country country) {
		int slot = match.getBaseSlot(country);
		builder.append(country.getId())
				.append(" | slot=")
				.append(slot >= 0 ? slot : "?");
		if (slot >= 0) {
			appendSlotSummary(builder, fightLevel, center, slot, country);
		}
		builder.append(" | fighters=")
				.append(match.countLivingFighters(fightLevel, country))
				.append(" | reserve=")
				.append(match.getReserveSize(country))
				.append('\n');
	}

	private static void appendSlotSummary(
			StringBuilder builder,
			ServerLevel fightLevel,
			BlockPos center,
			int slot,
			Country country) {
		double angle = ArenaCountryBaseLayout.slotAngleDegrees(slot);
		BlockPos core = ArenaCountryBaseLayout.corePosition(center, slot);
		BlockPos visualCore = ArenaCountryBaseLayout.visualCorePosition(center, slot);
		BlockPos logicalCore = ArenaCountryBaseLayout.coreDamagePosition(center, slot);
		BlockPos approach = ArenaCountryBaseLayout.resolveCoreApproachPosition(fightLevel, center, slot);
		BlockPos spawn = ArenaCountryBaseLayout.spawnZoneCenter(center, slot);
		int safePoints = ArenaCountryBaseLayout.collectSafeSpawnFeet(fightLevel, center, slot).size();
		BlockPos firstValid = null;
		for (BlockPos point : ArenaCountryBaseLayout.spawnZonePoints(center, slot)) {
			BlockPos feet = ArenaCountryBaseLayout.resolveFeetOnSurface(fightLevel, center, point);
			if (feet != null && ArenaPositions.isValidSpawn(fightLevel, center, feet)) {
				if (firstValid == null) {
					firstValid = feet;
				}
			}
		}
		boolean path = firstValid != null;
		if (path) {
			for (BlockPos safeFeet : ArenaCountryBaseLayout.collectSafeSpawnFeet(fightLevel, center, slot)) {
				if (!ArenaLayoutPathfinder.hasNavigationPathToAnyTarget(
						fightLevel,
						safeFeet,
						ArenaLayoutPathfinder.centralTargets(center))) {
					path = false;
					break;
				}
			}
		}
		boolean coreExists = fightLevel != null && fightLevel.getBlockState(core).blocksMotion();
		boolean slotValid = coreExists
				&& safePoints >= ArenaCountryBaseLayout.MIN_SAFE_SPAWN_POINTS
				&& path;
		boolean protectedCore = country != null
				&& fightLevel != null
				&& ArenaCoreManager.get().isCoreProtected(fightLevel, country);

		builder.append(" | angle=")
				.append(String.format(Locale.ROOT, "%.0f°", angle))
				.append(" | core=")
				.append(core.getX()).append(',').append(core.getZ())
				.append(" | spawn=")
				.append(spawn.getX()).append(',').append(spawn.getZ())
				.append(" | safePts=")
				.append(safePoints)
				.append(" | path=")
				.append(path)
				.append(" | coreBuilt=")
				.append(coreExists)
				.append(" | visualCore=")
				.append(visualCore.toShortString())
				.append(" | logicalCore=")
				.append(logicalCore.toShortString())
				.append(" | positionsMatch=")
				.append(visualCore.equals(logicalCore))
				.append(" | approach=")
				.append(approach.toShortString())
				.append(" | displayExists=")
				.append(ArenaCoreDisplayManager.get().hasDisplay(slot))
				.append(" | displayPos=")
				.append(ArenaCoreDisplayManager.get().displayPosition(center, slot).toShortString())
				.append(" | ISSUE:")
				.append(slotValid ? "no" : "yes");
		if (country != null) {
			builder.append(" | prot=").append(protectedCore);
		}
	}

	private static int layoutValidate(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		BlockPos center = resolveCenter(server, source);
		ServerLevel fightLevel = ArenaSpawns.resolveFightLevel(server, source.getLevel());

		ArenaCountryLayoutValidator.ValidationResult result =
				ArenaCountryLayoutValidator.validateAllSlots(fightLevel, center);

		int maxSafe = ArenaCountryBaseLayout.BASE_SLOT_COUNT * ArenaCountryBaseLayout.SPAWN_ZONE_POINT_COUNT;
		StringBuilder builder = new StringBuilder();
		builder.append(result.ok() ? "Layout: OK\n" : "Layout: FAILED\n");
		builder.append("Valid slots: ")
				.append(result.validSlots())
				.append('/')
				.append(result.physicalBases())
				.append('\n');
		builder.append("Safe spawn points: ")
				.append(result.totalSafeSpawnPoints())
				.append('/')
				.append(maxSafe)
				.append('\n');
		builder.append("No path: ").append(result.slotsWithoutPath()).append('\n');
		builder.append("Intersections: ")
				.append(result.baseIntersections() + result.spawnIntersections())
				.append(" (bases=")
				.append(result.baseIntersections())
				.append(" spawns=")
				.append(result.spawnIntersections())
				.append(")\n");
		builder.append("adjacent distance≈")
				.append(String.format(Locale.ROOT, "%.1f", result.adjacentBaseDistance()))
				.append(" blocks\n");

		if (!result.errors().isEmpty()) {
			builder.append("Errors:\n");
			for (String error : result.errors()) {
				builder.append("- ").append(error).append('\n');
			}
		}

		for (ArenaCountryLayoutValidator.SlotReport slot : result.slots()) {
			builder.append(String.format(
					Locale.ROOT,
					"slot %2d angle=%5.1f safe=%d path=%s coreBuilt=%s valid=%s%s\n",
					slot.slot(),
					slot.angleDegrees(),
					slot.safeSpawnPoints(),
					slot.pathToCenter(),
					slot.coreBuilt(),
					slot.valid(),
					slot.issue() != null ? " ISSUE:" + slot.issue() : ""));
		}

		source.sendSuccess(() -> Component.literal(builder.toString()), false);
		return result.ok() ? 1 : 0;
	}

	private static int layoutDebug(CommandContext<CommandSourceStack> context, boolean enabled) {
		ArenaCountryLayoutDebug.setEnabled(enabled);
		context.getSource().sendSuccess(
				() -> Component.literal("Arena layout debug particles: " + (enabled ? "ON" : "OFF")),
				false);
		return 1;
	}
}
