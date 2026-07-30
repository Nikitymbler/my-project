package com.nikita.arenaofnations;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Operator diagnostics for base-flag lifecycle and spawn/exit geometry.
 * One-shot report — not ticked.
 */
final class ArenaVisualStatusCommands {
	private ArenaVisualStatusCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("arena_visual_status")
						.requires(source -> source.hasPermission(2))
						.executes(context -> {
							context.getSource().sendSuccess(
									() -> Component.literal(buildReport(context.getSource())),
									false);
							return Command.SINGLE_SUCCESS;
						})));
	}

	static String buildReport(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaCoreRescueManager rescue = ArenaCoreRescueManager.get();
		ArenaHudSnapshot snapshot = ArenaRoundHudSync.buildSnapshot(server);
		Set<Country> participants = new LinkedHashSet<>(match.getCurrentRoundCountries());
		participants.addAll(match.getActiveCountries());

		StringBuilder out = new StringBuilder();
		out.append("Arena visual status:\n");
		out.append("match_state=").append(match.getState()).append('\n');
		out.append("round_participants=").append(participants.size()).append('\n');
		out.append("snapshot_countries=").append(snapshot.countries().size()).append('\n');
		out.append("base_flag_source=ArenaBaseMarkerRenderer (client world billboard, not entity)\n");
		out.append("fighter_flag_render_paths=1 (ArenaFighterRenderer->ArenaFighterOverheadRenderer)\n");
		out.append("fighter_model=PlayerModel(wide_steve_4px_arms)\n");
		out.append("fighter_shared_skin=arena_of_nations:textures/entity/fighter/medieval_soldier.png\n");
		out.append("usingDefaultSteve=false (code path)\n");
		out.append("usingPlayerSkinManager=false (code path)\n");
		out.append("weapon_mode=ITEM_STACK\n");
		out.append("weaponVisualType=MEDIEVAL_GLAIVE\n");
		out.append("weaponRenderPaths=1\n");
		out.append("tridentRenderPaths=0\n");
		out.append("itemInHandLayerRegistered=true\n");
		out.append("customWeaponLayerRegistered=false\n");
		out.append("active_weapon_render_paths=1\n");
		out.append("weaponModelResource=arena_of_nations:models/item/medieval_spear.json\n");
		out.append("weaponTextureResource=arena_of_nations:textures/item/medieval_spear.png\n");
		out.append("weaponAttachedToRightArm=true\n");
		out.append("weaponAngleDegrees=70\n");
		out.append("weaponScale=0.966\n");
		out.append("capeEnabled=false\n");
		out.append("capeRenderLayers=0\n");
		out.append("capeResourcesLoaded=0\n");
		out.append("fighter_main_weapon=arena_of_nations:medieval_spear\n");
		out.append("client_resource_checks=use /arena_visual_status_client for exists/dimensions/runtime texture state\n");

		if (participants.isEmpty() && snapshot.countries().isEmpty()) {
			out.append("countries: (none)\n");
		}

		LinkedHashSet<Country> listed = new LinkedHashSet<>(participants);
		for (ArenaHudCountryState row : snapshot.countries()) {
			listed.add(row.country());
		}

		for (Country country : listed) {
			boolean participant = participants.contains(country)
					|| snapshot.countries().stream().anyMatch(r -> r.country() == country);
			boolean eliminated = rescue.isEliminated(country);
			int slot = match.getBaseSlot(country);
			boolean baseFlagExpected = ArenaBaseFlagVisibility.shouldShow(participant, eliminated, slot);
			boolean baseFlagEntityExists = false; // base markers are client-rendered quads, not entities
			boolean baseFlagVisible = baseFlagExpected;
			String hide = ArenaBaseFlagVisibility.hideReason(participant, eliminated, slot);
			int living = match.getLiveFighterCount(server, country);
			int reserve = match.getReserveSize(country);
			String baseFlagUuid = "none";
			int baseFlagDuplicates = 0;
			BlockPos center = BlockPos.containing(match.getMatchCenter());
			BlockPos spawn = slot >= 0 ? ArenaCountryBaseLayout.spawnZoneCenter(center, slot) : BlockPos.ZERO;
			int floorY = spawn.getY() - 1;
			BlockPos exit = slot >= 0
					? spawn.relative(ArenaCountryBaseLayout.inwardDirection(slot), 12)
					: BlockPos.ZERO;
			BlockPos rally = slot >= 0
					? computeRallyPoint(center, country)
					: BlockPos.ZERO;
			out.append(String.format(
					Locale.ROOT,
					"- %s participant=%s eliminated=%s baseFlagExpected=%s baseFlagEntityExists=%s baseFlagVisible=%s baseFlagUUID=%s baseFlagDuplicates=%d hide=%s living=%d reserve=%d slot=%d spawnY=%d floorY=%d exit=%s rally=%s\n",
					country.getCode(),
					participant,
					eliminated,
					baseFlagExpected,
					baseFlagEntityExists,
					baseFlagVisible,
					baseFlagUuid,
					baseFlagDuplicates,
					hide,
					living,
					reserve,
					slot,
					spawn.getY(),
					floorY,
					exit.toShortString(),
					rally.toShortString()));
		}

		out.append("note=baseFlagEntityExists=false because active path is client renderer, not display entities\n");
		return out.toString().trim();
	}

	private static BlockPos computeRallyPoint(BlockPos arenaCenter, Country enemyCountry) {
		BlockPos approach = ArenaPositions.getCoreApproachPosition(arenaCenter, enemyCountry);
		double cx = arenaCenter.getX() + 0.5D;
		double cz = arenaCenter.getZ() + 0.5D;
		double ax = approach.getX() + 0.5D;
		double az = approach.getZ() + 0.5D;
		double dx = ax - cx;
		double dz = az - cz;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len > 1.0E-3) {
			dx = dx / len * 12.0D;
			dz = dz / len * 12.0D;
		}
		return BlockPos.containing(cx + dx, arenaCenter.getY() + 1, cz + dz);
	}
}
