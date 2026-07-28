package com.nikita.arenaofnations.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import com.nikita.arenaofnations.ArenaFighterEntity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Draws vanilla items in fighter hands for display only.
 * Does not read or write entity equipment slots.
 */
public class ArenaFighterHeldItemLayer
		extends RenderLayer<ArenaFighterEntity, PlayerModel<ArenaFighterEntity>> {
	private final ItemInHandRenderer itemInHandRenderer;

	public ArenaFighterHeldItemLayer(
			RenderLayerParent<ArenaFighterEntity, PlayerModel<ArenaFighterEntity>> parent,
			ItemInHandRenderer itemInHandRenderer) {
		super(parent);
		this.itemInHandRenderer = itemInHandRenderer;
	}

	@Override
	public void render(
			PoseStack poseStack,
			MultiBufferSource buffer,
			int packedLight,
			ArenaFighterEntity entity,
			float limbSwing,
			float limbSwingAmount,
			float partialTick,
			float ageInTicks,
			float netHeadYaw,
			float headPitch) {
		ArenaFighterEquipmentVisuals.Hands hands = ArenaFighterEquipmentVisuals.handsFor(entity);
		ItemStack mainHand = hands.mainHand();
		ItemStack offHand = hands.offHand();
		if (mainHand.isEmpty() && offHand.isEmpty()) {
			return;
		}

		poseStack.pushPose();
		renderArmItem(entity, mainHand, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, poseStack, buffer, packedLight);
		renderArmItem(entity, offHand, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidArm.LEFT, poseStack, buffer, packedLight);
		poseStack.popPose();
	}

	private void renderArmItem(
			ArenaFighterEntity entity,
			ItemStack stack,
			ItemDisplayContext displayContext,
			HumanoidArm arm,
			PoseStack poseStack,
			MultiBufferSource buffer,
			int packedLight) {
		if (stack.isEmpty()) {
			return;
		}

		poseStack.pushPose();
		getParentModel().translateToHand(arm, poseStack);
		poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

		boolean leftHand = arm == HumanoidArm.LEFT;
		poseStack.translate((leftHand ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);

		// Face the shield outward on the offhand (same side convention as vanilla left-hand items).
		if (leftHand && stack.is(Items.SHIELD)) {
			poseStack.translate(0.0F, 0.05F, 0.0F);
		}

		itemInHandRenderer.renderItem(entity, stack, displayContext, leftHand, poseStack, buffer, packedLight);
		poseStack.popPose();
	}
}
