package com.nikita.arenaofnations.client;

import com.mojang.blaze3d.vertex.PoseStack;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.WolfModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders arena fighters. RU Scout uses the custom Blockbench model;
 * all other country/tier combinations keep the temporary wolf fallback.
 */
public class ArenaFighterRenderer extends MobRenderer<ArenaFighterEntity, EntityModel<ArenaFighterEntity>> {
	private static final ResourceLocation RU_SCOUT_TEXTURE =
			ArenaOfNations.id("textures/entity/ru_scout.png");

	private final WolfModel<ArenaFighterEntity> wolfModel;
	private final RuScoutModel ruScoutModel;

	public ArenaFighterRenderer(EntityRendererProvider.Context context) {
		super(context, new WolfModel<>(context.bakeLayer(ModelLayers.WOLF)), 0.5F);
		this.wolfModel = (WolfModel<ArenaFighterEntity>) this.model;
		this.ruScoutModel = new RuScoutModel(context.bakeLayer(RuScoutModel.LAYER_LOCATION));
	}

	@Override
	public void render(
			ArenaFighterEntity entity,
			float entityYaw,
			float partialTicks,
			PoseStack poseStack,
			MultiBufferSource buffer,
			int packedLight) {
		if (isRuScout(entity)) {
			this.model = this.ruScoutModel;
			this.shadowRadius = 0.5F;
		} else {
			this.model = this.wolfModel;
			this.shadowRadius = 0.5F;
		}
		super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(ArenaFighterEntity entity) {
		if (isRuScout(entity)) {
			return RU_SCOUT_TEXTURE;
		}
		return entity.getTexture();
	}

	private static boolean isRuScout(ArenaFighterEntity entity) {
		return entity.getArenaCountry() == Country.RU
				&& entity.getArenaTier() == FighterTier.SCOUT;
	}
}
