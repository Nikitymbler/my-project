package com.nikita.arenaofnations.client;

import com.mojang.blaze3d.vertex.PoseStack;

import com.nikita.arenaofnations.ArenaFighterEntity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders every arena fighter with the shared wide Steve humanoid model
 * and country/tier skin selected by ArenaFighterVisuals.
 * Overhead country flag + health bar replace the vanilla name tag.
 */
public class ArenaFighterRenderer extends MobRenderer<ArenaFighterEntity, PlayerModel<ArenaFighterEntity>> {
	public ArenaFighterRenderer(EntityRendererProvider.Context context) {
		super(context, new ArenaFighterHumanoidModel(context.bakeLayer(ArenaFighterModels.HUMANOID_LAYER)), 0.5F);
		ensureOuterLayersVisible(this.model);
		addLayer(new ArenaFighterHeldItemLayer(this, context.getItemInHandRenderer()));
	}

	@Override
	public void render(
			ArenaFighterEntity entity,
			float entityYaw,
			float partialTick,
			PoseStack poseStack,
			MultiBufferSource buffer,
			int packedLight) {
		super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
		ArenaFighterOverheadRenderer.render(entity, this.entityRenderDispatcher, poseStack, buffer, partialTick);
	}

	@Override
	protected boolean shouldShowName(ArenaFighterEntity entity) {
		return false;
	}

	@Override
	protected void renderNameTag(
			ArenaFighterEntity entity,
			Component displayName,
			PoseStack poseStack,
			MultiBufferSource buffer,
			int packedLight,
			float partialTick) {
		// Vanilla text name tag disabled; overhead flag renderer draws the indicator instead.
	}

	@Override
	public ResourceLocation getTextureLocation(ArenaFighterEntity entity) {
		return ArenaFighterVisuals.texture(entity);
	}

	/**
	 * LivingEntityRenderer already multiplies by entity.getScale() (attribute/hitbox scale).
	 * Compensate so the on-screen size matches the visual profile without changing hitbox.
	 */
	@Override
	protected void scale(ArenaFighterEntity entity, PoseStack poseStack, float partialTick) {
		float attributeScale = Math.max(entity.getScale(), 0.0001F);
		float visualScale = ArenaFighterVisuals.visualScale(entity);
		float factor = visualScale / attributeScale;
		poseStack.scale(factor, factor, factor);
	}

	@Override
	protected float getShadowRadius(ArenaFighterEntity entity) {
		return 0.5F * ArenaFighterVisuals.visualScale(entity);
	}

	private static void ensureOuterLayersVisible(PlayerModel<ArenaFighterEntity> model) {
		model.setAllVisible(true);
		model.hat.visible = true;
		model.jacket.visible = true;
		model.leftSleeve.visible = true;
		model.rightSleeve.visible = true;
		model.leftPants.visible = true;
		model.rightPants.visible = true;
	}
}