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
								() -> Component.literal("Overlay HTTPS server (8766 whitelist) restarted."),
								false);
						return Command.SINGLE_SUCCESS;
					}));
		});
	}

	private static String buildStatus() {
		ArenaConfig config = ArenaConfig.get();
		ArenaOverlayStateService overlay = ArenaOverlayStateService.get();
		ArenaMatchManager match = ArenaMatchManager.get();
		net.minecraft.server.MinecraftServer server = ArenaOverlayHttpServer.getActiveServer();
		String snap = overlay.snapshotJson();
		boolean snapshotAvailable = snap != null && snap.contains("\"phase\"");
		boolean coreHpFieldsAvailable = snap != null
				&& snap.contains("\"coreHp\"")
				&& snap.contains("\"coreMaxHp\"");
		ArenaOverlayHttpsMaterial.CertificateStatus cert = ArenaOverlayHttpsMaterial.inspect();
		return "Overlay status:\n"
				+ "primaryOverlayMode=TIKTOK_WINDOW_CHROMA\n"
				+ "browserOverlayAvailable=true\n"
				+ "browserOverlayUrl=" + ArenaOverlayHttpServer.getLocalTikTokUrl() + '\n'
				+ "browserOverlayWindowUrl=" + ArenaOverlayHttpServer.getChromaOverlayUrl() + '\n'
				+ "browserOverlayTransparentUrl=" + ArenaOverlayHttpServer.getTransparentOverlayUrl() + '\n'
				+ "chromaKeyColor=#FF00FF\n"
				+ "nativeCanvasWidth=1080\n"
				+ "nativeCanvasHeight=1920\n"
				+ "cssRootScale=1\n"
				+ "coreHpFieldsAvailable=" + coreHpFieldsAvailable + '\n'
				+ "inGameHudEnabled=false\n"
				+ "inGameHudRendererRegistered=false\n"
				+ "inGameHudRenderPaths=0\n"
				+ "overlayMode=LOCAL_HTTPS_BROWSER\n"
				+ "overlayUrl=" + ArenaOverlayHttpServer.getLocalTikTokUrl() + '\n'
				+ "previewUrl=" + ArenaOverlayHttpServer.getLocalPreviewUrl() + '\n'
				+ "legacyAliasUrl=" + ArenaOverlayHttpServer.getLegacyAliasUrl() + '\n'
				+ "bind=" + ArenaOverlayHttpServer.getBindAddress() + '\n'
				+ "port=" + ArenaOverlayHttpServer.getRunningPort() + '\n'
				+ "httpsEnabled=" + config.isOverlayHttpsEnabled() + '\n'
				+ "certificateSource=" + blank(cert.certificateSource()) + '\n'
				+ "certificateConfigured=" + cert.certificateConfigured() + '\n'
				+ "serverRunning=" + ArenaOverlayHttpServer.isRunning() + '\n'
				+ "serverInstances=" + ArenaOverlayHttpServer.getInstanceCount() + '\n'
				+ "lastStartResult=" + blank(ArenaOverlayHttpServer.getLastStartResult()) + '\n'
				+ "snapshotAvailable=" + snapshotAvailable + '\n'
				+ "lastSnapshotAgeMs=" + overlay.lastSnapshotAgeMs() + '\n'
				+ "activeHttpThreads=" + ArenaOverlayHttpServer.getActiveThreadEstimate() + '\n'
				+ "pollingEndpoint=/arena/overlay-state\n"
				+ "streamToEarnPort=" + config.getS2eHttpPort() + '\n'
				+ "s2e_running=" + ArenaStreamToEarnHttpBridge.isRunning() + '\n'
				+ "snapshot_sequence=" + overlay.snapshotSequence() + '\n'
				+ "snapshot_countries=" + overlay.snapshotCountryCount() + '\n'
				+ "active_countries=" + match.getActiveCountries().size() + '\n'
				+ "overlayParticipantSource=" + overlay.participantSource() + '\n'
				+ "overlayRoundParticipants=" + blank(overlay.lastRoundParticipantCodes()) + '\n'
				+ "overlayEliminatedCountries=" + blank(overlay.lastEliminatedCodes()) + '\n'
				+ "overlayDisplayedCountries=" + blank(overlay.lastDisplayedCountryCodes()) + '\n'
				+ "overlayDisplayedCountryCount=" + overlay.lastDisplayedCountryCount() + '\n'
				+ "overlayEliminatedCardsVisible=0\n"
				+ "overlayLastRemovedCountry=" + blank(overlay.lastRemovedCountryCode()) + '\n'
				+ "overlayImmediatePushAfterElimination=" + overlay.lastImmediatePushAfterElimination() + '\n'
				+ "overlaySnapshotCountries=" + blank(overlay.lastDisplayedCountryCodes()) + '\n'
				+ "overlayWaitingHolderIncluded=" + overlay.lastWaitingHolderIncluded() + '\n'
				+ "overlayGridColumns=" + overlay.lastGridColumns() + '\n'
				+ "overlayCardSizeMode=" + overlay.lastCardSizeMode() + '\n'
				+ "overlayOverflowDetected="
				+ !ArenaOverlayLayout.fitsWithoutOverflow(overlay.snapshotCountryCount()) + '\n'
				+ "overlayCanvasWidth=" + ArenaOverlayLayout.CANVAS_WIDTH + '\n'
				+ "overlayCanvasHeight=" + ArenaOverlayLayout.CANVAS_HEIGHT + '\n'
				+ "browserOverlayDraggable=true\n"
				+ "browserOverlayEditModeSupported=true\n"
				+ "overlayLayoutPersistence=SERVER_CONFIG\n"
				+ "overlayLayoutConfigPath=" + blank(ArenaOverlayLayoutConfig.configPath().toString()) + '\n'
				+ "overlayLayoutVersion=" + ArenaOverlayLayoutConfig.VERSION + '\n'
				+ "overlayLayoutLoaded=" + ArenaOverlayLayoutConfig.isLoaded() + '\n'
				+ "overlayLayoutLastSaveSuccess=" + ArenaOverlayLayoutConfig.lastSaveSuccess() + '\n'
				+ "overlayLayoutLastSaveError=" + blank(ArenaOverlayLayoutConfig.lastSaveError()) + '\n'
				+ "battleXRatio=" + ArenaOverlayLayoutConfig.current().battle().xRatio() + '\n'
				+ "battleYRatio=" + ArenaOverlayLayoutConfig.current().battle().yRatio() + '\n'
				+ "top5XRatio=" + ArenaOverlayLayoutConfig.current().top5().xRatio() + '\n'
				+ "top5YRatio=" + ArenaOverlayLayoutConfig.current().top5().yRatio() + '\n'
				+ "overlayWorkspaceWidth=CLIENT_SIDE\n"
				+ "overlayWorkspaceHeight=CLIENT_SIDE\n"
				+ "overlayModulesFullyInsideWorkspace=true\n"
				+ "overlayLegacyLocalStorageMigrated=" + ArenaOverlayLayoutConfig.legacyLocalStorageMigrated() + '\n'
				+ "fighterRoundRecordCountry=" + blank(recordCountryCode(server)) + '\n'
				+ "fighterRoundRecordCount=" + ArenaScoreManager.getFighterRoundRecordCount(server) + '\n'
				+ "fighterRoundRecordPersistent=true\n"
				+ "fighterRoundRecordStorage=SAVED_DATA\n"
				+ "fighterRoundRecordVisible=" + ArenaOverlayLayoutConfig.current().record().visible() + '\n'
				+ "fighterRoundRecordXRatio=" + ArenaOverlayLayoutConfig.current().record().xRatio() + '\n'
				+ "fighterRoundRecordYRatio=" + ArenaOverlayLayoutConfig.current().record().yRatio() + '\n'
				+ "currentRoundFightersSent=" + blank(currentRoundSentDiag()) + '\n'
				+ "statsResetAllowed=" + ArenaStatsResetService.isResetAllowed() + '\n'
				+ "lastStatsResetType=" + blank(ArenaStatsResetService.lastResetType()) + '\n'
				+ "lastStatsResetSuccess=" + ArenaStatsResetService.lastResetSuccess() + '\n'
				+ "lastStatsResetError=" + blank(ArenaStatsResetService.lastResetError()) + '\n'
				+ "runtimeReserveSettingsAvailable=true\n"
				+ "runtimeReserveReleaseBatch=" + ArenaReserveRuntimeSettings.get().getReserveReleaseBatch() + '\n'
				+ "runtimeReserveReleaseBatchMin=" + ArenaReserveRuntimeSettings.MIN_BATCH + '\n'
				+ "runtimeReserveReleaseBatchMax=" + ArenaReserveRuntimeSettings.MAX_BATCH + '\n'
				+ "battleModulePosition=SERVER_RATIO\n"
				+ "top5ModulePosition=SERVER_RATIO\n"
				+ "top5Visible=" + ArenaOverlayLayoutConfig.current().top5().visible() + '\n'
				+ "top5Source=" + ArenaTopCountriesRanking.SOURCE + '\n'
				+ "top5Countries=" + blank(overlay.lastTop5Countries()) + '\n'
				+ "top5LastUpdate=" + (overlay.lastTop5UpdateAgeMs() < 0
						? "-"
						: overlay.lastTop5UpdateAgeMs() + "ms_ago") + '\n'
				+ "overlayLayoutStorage=SERVER_CONFIG\n"
				+ "http_requests=" + overlay.requestCount() + '\n'
				+ "lastError=" + blank(firstNonBlank(
						ArenaOverlayHttpServer.getLastStartError(),
						cert.error(),
						ArenaOverlayHttpIO.lastError()));
	}

	private static String recordCountryCode(net.minecraft.server.MinecraftServer server) {
		if (server == null) {
			return "-";
		}
		Country country = ArenaScoreManager.getFighterRoundRecordCountry(server);
		return country == null ? "-" : country.getCode();
	}

	private static String currentRoundSentDiag() {
		StringBuilder sent = new StringBuilder();
		for (var entry : ArenaMatchManager.get().getFightersSentThisRoundSnapshot().entrySet()) {
			if (sent.length() > 0) {
				sent.append(',');
			}
			sent.append(entry.getKey().getCode()).append(':').append(entry.getValue());
		}
		return sent.toString();
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String firstNonBlank(String a, String b, String c) {
		if (a != null && !a.isBlank()) {
			return a;
		}
		if (b != null && !b.isBlank()) {
			return b;
		}
		if (c != null && !c.isBlank()) {
			return c;
		}
		return "";
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
