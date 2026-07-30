package com.nikita.arenaofnations;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class ArenaOverlayCommands {
	private ArenaOverlayCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("arena_overlay_status")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						context.getSource().sendSuccess(
								() -> Component.literal(buildStatus()),
								false);
						return Command.SINGLE_SUCCESS;
					}));
			dispatcher.register(Commands.literal("arena_overlay_dump")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						ArenaOverlayStateService.pushNow(context.getSource().getServer());
						context.getSource().sendSuccess(
								() -> Component.literal(buildDump(context.getSource().getServer())),
								false);
						return Command.SINGLE_SUCCESS;
					}));
			dispatcher.register(Commands.literal("arena_overlay_restart")
					.requires(source -> source.hasPermission(2))
					.executes(context -> {
						ArenaOverlayHttpServer.restart();
						context.getSource().sendSuccess(
								() -> Component.literal("Overlay HTTP server (8766 whitelist) restarted."),
								false);
						return Command.SINGLE_SUCCESS;
					}));
		});
	}

	private static String buildStatus() {
		ArenaConfig config = ArenaConfig.get();
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		ArenaOverlayPublicUrl.ValidationResult publicUrl = config.getOverlayPublicUrlValidation();
		return "Overlay status:\n"
				+ "overlay_http_enabled=" + config.isOverlayHttpEnabled() + '\n'
				+ "local_bind=" + ArenaOverlayHttpServer.getBindAddress() + '\n'
				+ "local_port=" + ArenaOverlayHttpServer.getRunningPort() + '\n'
				+ "local_running=" + ArenaOverlayHttpServer.isRunning() + '\n'
				+ "local_health=" + ArenaOverlayHttpServer.getLocalHealthUrl() + '\n'
				+ "local_tiktok=" + ArenaOverlayHttpServer.getLocalTikTokUrl() + '\n'
				+ "local_state=" + ArenaOverlayHttpServer.getLocalStateUrl() + '\n'
				+ "instances=" + ArenaOverlayHttpServer.getInstanceCount() + '\n'
				+ "active_http_threads~=" + ArenaOverlayHttpServer.getActiveThreadEstimate() + '\n'
				+ "last_server_error=" + blank(ArenaOverlayHttpServer.getLastStartError()) + '\n'
				+ "last_handler_error=" + blank(ArenaOverlayHttpIO.lastError()) + '\n'
				+ "last_state_request_ms=" + ArenaOverlayHttpIO.lastStateRequestGameTime() + '\n'
				+ "public_enabled=" + config.isOverlayPublicEnabled() + '\n'
				+ "public_url_valid=" + publicUrl.valid() + '\n'
				+ "public_url=" + (publicUrl.valid() ? publicUrl.url() : ("(invalid: " + publicUrl.reason() + ")")) + '\n'
				+ "public_token_shown=false\n"
				+ "s2e_port=" + config.getS2eHttpPort() + '\n'
				+ "s2e_running=" + ArenaStreamToEarnHttpBridge.isRunning() + '\n'
				+ "s2e_endpoints_active=" + ArenaStreamToEarnHttpBridge.areS2eEndpointsActive() + '\n'
				+ "public_has_gift_chat_routes=false\n"
				+ "api_aliases=/arena/overlay-state,/api/arena/state\n"
				+ "snapshot_sequence=" + overlay.snapshotSequence() + '\n'
				+ "snapshot_countries=" + overlay.snapshotCountryCount() + '\n'
				+ "active_countries=" + match.getActiveCountries().size() + '\n'
				+ "round_countries=" + match.getCurrentRoundCountries().size() + '\n'
				+ "last_snapshot_age_ms=" + overlay.lastSnapshotAgeMs() + '\n'
				+ "http_requests=" + overlay.requestCount() + '\n'
				+ "hud_mode=" + ArenaHudManager.get().getHudMode();
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String buildDump(net.minecraft.server.MinecraftServer server) {
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		int activeFighters = 0;
		int reserve = 0;
		for (Country country : match.getCurrentRoundCountries()) {
			activeFighters += match.getLiveFighterCount(server, country);
			reserve += match.getReserveSize(country);
		}
		StringBuilder builder = new StringBuilder("Overlay dump:\n");
		builder.append("sequence=").append(overlay.snapshotSequence()).append('\n');
		builder.append("phase=").append(match.getState()).append('\n');
		builder.append("remaining=").append(match.getRemainingSeconds()).append("s\n");
		builder.append("activeCountryCount=").append(match.getActiveCountries().size()).append('\n');
		builder.append("countriesSize=").append(overlay.snapshotCountryCount()).append('\n');
		builder.append("totalActiveFighters=").append(activeFighters).append('\n');
		builder.append("totalReserve=").append(reserve).append('\n');
		builder.append("first5:");
		int shown = 0;
		for (Country country : match.getCurrentRoundCountries()) {
			if (shown >= 5) {
				break;
			}
			ArenaCoreState core = ArenaCoreManager.get().getState(country);
			builder.append('\n')
					.append("  ")
					.append(country.getCode())
					.append(" fighters=")
					.append(match.getLiveFighterCount(server, country))
					.append('/')
					.append(match.getReserveSize(country))
					.append(" core=")
					.append(Math.round(core.getCurrentHealth()))
					.append('/')
					.append(Math.round(core.getMaxHealth()));
			shown++;
		}
		if (shown == 0) {
			builder.append(" (empty)");
		}
		return builder.toString();
	}
}
