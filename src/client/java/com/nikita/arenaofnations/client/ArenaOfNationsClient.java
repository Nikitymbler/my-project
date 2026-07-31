package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaBaseFlagVisibility;
import com.nikita.arenaofnations.ArenaClientPerfConfig;
import com.nikita.arenaofnations.ArenaEntities;
import com.nikita.arenaofnations.ArenaHudCountryState;
import com.nikita.arenaofnations.ArenaHudSnapshot;
import com.nikita.arenaofnations.ArenaOfNations;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public class ArenaOfNationsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ArenaClientPerfRuntime.register();
		EntityModelLayerRegistry.registerModelLayer(
				ArenaFighterModels.HUMANOID_LAYER,
				ArenaFighterModels::createHumanoidLayer);
		EntityRendererRegistry.register(ArenaEntities.ARENA_FIGHTER, ArenaFighterRenderer::new);
		ArenaFighterVisualEffects.register();
		ArenaRoundHudClient.register();
		// In-game screen HUD permanently off — browser window chroma overlay is the match HUD.
		ArenaRoundHudRenderer.register();
		ArenaBaseMarkerRenderer.register();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("arena_hud_debug_client")
					.executes(context -> {
						Minecraft mc = Minecraft.getInstance();
						int w = mc.getWindow().getGuiScaledWidth();
						int h = mc.getWindow().getGuiScaledHeight();
						String report = ArenaRoundHudRenderer.debugReport(w, h);
						context.getSource().sendFeedback(Component.literal(report));
						return 1;
					}));
			dispatcher.register(ClientCommandManager.literal("arena_base_markers")
					.then(ClientCommandManager.literal("on").executes(context -> {
						ArenaBaseMarkerSettings.setEnabled(true);
						context.getSource().sendFeedback(Component.literal("Base markers: ON"));
						return 1;
					}))
					.then(ClientCommandManager.literal("off").executes(context -> {
						ArenaBaseMarkerSettings.setEnabled(false);
						context.getSource().sendFeedback(Component.literal("Base markers: OFF"));
						return 1;
					}))
					.then(ClientCommandManager.literal("status").executes(context -> {
						int entries = ArenaRoundHudClient.getSnapshotIfFresh().countries().size();
						context.getSource().sendFeedback(Component.literal(ArenaBaseMarkerSettings.statusReport(entries)));
						return 1;
					}))
					.executes(context -> {
						int entries = ArenaRoundHudClient.getSnapshotIfFresh().countries().size();
						context.getSource().sendFeedback(Component.literal(ArenaBaseMarkerSettings.statusReport(entries)));
						return 1;
					}));
			dispatcher.register(ClientCommandManager.literal("arena_visual_status_client")
					.executes(context -> {
						context.getSource().sendFeedback(Component.literal(buildClientVisualStatus()));
						return 1;
					}));
			dispatcher.register(ClientCommandManager.literal("arena_client_perf")
					.executes(context -> {
						context.getSource().sendFeedback(Component.literal(ArenaClientPerfRuntime.statusReport()));
						return 1;
					}));
			dispatcher.register(ClientCommandManager.literal("arena_client_config_reload")
					.executes(context -> {
						ArenaClientPerfConfig.reload();
						ArenaClientPerfRuntime.onConfigReloaded();
						context.getSource().sendFeedback(
								Component.literal("Клиентские настройки Arena of Nations перезагружены."));
						return 1;
					}));
		});

		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
				new SimpleSynchronousResourceReloadListener() {
					@Override
					public ResourceLocation getFabricId() {
						return ArenaOfNations.id("fighter_skin_cache");
					}

					@Override
					public void onResourceManagerReload(ResourceManager resourceManager) {
						ArenaFighterVisuals.clearTextureCache();
						ArenaFighterFlagVisuals.clearTextureCache();
					}
				});
	}

	private static String buildClientVisualStatus() {
		ArenaFighterVisuals.ensureSkinDiagnosticsLogged();
		ArenaFighterEquipmentVisuals.ensureWeaponDiagnosticsLogged();
		ArenaHudSnapshot snapshot = ArenaRoundHudClient.getSnapshotIfFresh();
		int visibleBase = 0;
		StringBuilder countries = new StringBuilder();
		for (ArenaHudCountryState row : snapshot.countries()) {
			boolean show = ArenaBaseFlagVisibility.shouldShow(row);
			if (show) {
				visibleBase++;
			}
			countries.append(String.format(
					java.util.Locale.ROOT,
					"\n- %s participant=true eliminated=%s baseFlagVisible=%s hide=%s living=%d reserve=%d",
					row.country().getCode(),
					row.eliminated(),
					show,
					ArenaBaseFlagVisibility.hideReason(row),
					row.aliveFighters(),
					row.reserveCount()));
		}

		return "Arena visual status (client):\n"
				+ "match_state=" + snapshot.state() + '\n'
				+ "fighter_entity_type=" + ArenaFighterVisuals.FIGHTER_ENTITY_TYPE_ID + '\n'
				+ "fighter_renderer_class=" + ArenaFighterVisuals.RENDERER_CLASS_NAME + '\n'
				+ "fighter_model_class=" + ArenaFighterVisuals.MODEL_CLASS_NAME + '\n'
				+ "active_texture_resource=" + ArenaFighterVisuals.sharedTexture() + '\n'
				+ "texture_exists_in_resources=" + ArenaFighterVisuals.sharedTextureExists() + '\n'
				+ "texture_dimensions=" + ArenaFighterVisuals.sharedTextureDimensions() + '\n'
				+ "usingDefaultSteve=" + ArenaFighterVisuals.usingDefaultSteve() + '\n'
				+ "usingPlayerSkinManager=" + ArenaFighterVisuals.usingPlayerSkinManager() + '\n'
				+ "base_markers_enabled=" + ArenaBaseMarkerSettings.isEnabled() + '\n'
				+ "base_markers_active=" + ArenaBaseMarkerSettings.lastActiveMarkers() + '\n'
				+ "base_markers_rendered=" + ArenaBaseMarkerSettings.lastRenderedMarkers() + '\n'
				+ "base_flag_visible_count=" + visibleBase + '\n'
				+ "fighter_flag_render_paths=" + ArenaFighterFlagVisuals.FIGHTER_FLAG_RENDER_PATH_COUNT
				+ " (" + ArenaFighterFlagVisuals.FIGHTER_FLAG_RENDER_PATH + ")\n"
				+ "fighter_model=PlayerModel(wide_steve_4px_arms)\n"
				+ "shared_skin_requested=" + ArenaFighterVisuals.sharedSkinResourceId() + '\n'
				+ "shared_skin_resolved=" + ArenaFighterVisuals.resolvedSkinResourceId() + '\n'
				+ "weapon_mode=" + ArenaFighterEquipmentVisuals.WEAPON_MODE + '\n'
				+ "weaponVisualType=" + ArenaFighterEquipmentVisuals.WEAPON_VISUAL_TYPE + '\n'
				+ "weaponRenderPaths=" + ArenaFighterEquipmentVisuals.ACTIVE_WEAPON_RENDER_PATHS + '\n'
				+ "tridentRenderPaths=" + ArenaFighterEquipmentVisuals.TRIDENT_RENDER_PATHS + '\n'
				+ "main_hand_item_id=" + ArenaFighterEquipmentVisuals.mainHandItemId() + '\n'
				+ "itemInHandLayerRegistered=" + ArenaFighterHeldItemLayer.ITEM_IN_HAND_LAYER_REGISTERED + '\n'
				+ "customWeaponLayerRegistered=" + ArenaFighterHeldItemLayer.CUSTOM_WEAPON_LAYER_REGISTERED + '\n'
				+ "weaponModelResource=" + ArenaFighterEquipmentVisuals.SPEAR_MODEL_RESOURCE + '\n'
				+ "weaponTextureResource=" + ArenaFighterEquipmentVisuals.SPEAR_TEXTURE_RESOURCE + '\n'
				+ "weaponModelExists=" + ArenaFighterEquipmentVisuals.spearModelExists() + '\n'
				+ "weaponTextureExists=" + ArenaFighterEquipmentVisuals.spearTextureExists() + '\n'
				+ "weaponAttachedToRightArm=" + ArenaFighterEquipmentVisuals.WEAPON_ATTACHED_TO_RIGHT_ARM + '\n'
				+ "weaponAngleDegrees=" + ArenaFighterEquipmentVisuals.WEAPON_ANGLE_DEGREES + '\n'
				+ "weaponScale=" + ArenaFighterEquipmentVisuals.WEAPON_SCALE + '\n'
				+ "active_weapon_render_paths=" + ArenaFighterEquipmentVisuals.ACTIVE_WEAPON_RENDER_PATHS + '\n'
				+ "capeEnabled=false\n"
				+ "capeRenderLayers=0\n"
				+ "capeResourcesLoaded=0\n"
				+ "fighter_flag_last_hide=" + ArenaFighterFlagVisuals.lastHideReasonLabel() + '\n'
				+ "flag_show_dist=" + (int) ArenaFighterFlagVisuals.SHOW_FLAG_DIST
				+ " hide_hysteresis=" + (int) ArenaFighterFlagVisuals.HIDE_FLAG_DIST
				+ countries;
	}
}
