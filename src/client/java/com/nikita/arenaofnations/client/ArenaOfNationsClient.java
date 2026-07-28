package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaEntities;
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
		EntityModelLayerRegistry.registerModelLayer(
				ArenaFighterModels.HUMANOID_LAYER,
				ArenaFighterModels::createHumanoidLayer);
		EntityRendererRegistry.register(ArenaEntities.ARENA_FIGHTER, ArenaFighterRenderer::new);
		ArenaFighterVisualEffects.register();
		ArenaRoundHudClient.register();
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
					}
				});
	}
}
