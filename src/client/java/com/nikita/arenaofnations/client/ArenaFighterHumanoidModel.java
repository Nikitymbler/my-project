package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaFighterEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.item.ItemStack;

/**
 * Wide Steve model. Attack poses come only from vanilla {@link PlayerModel}
 * via {@link #attackTime}; no custom wind-up / strike / recovery overrides.
 */
public class ArenaFighterHumanoidModel extends PlayerModel<ArenaFighterEntity> {
	public ArenaFighterHumanoidModel(ModelPart root) {
		super(root, false);
	}

	@Override
	public void setupAnim(
			ArenaFighterEntity entity,
			float limbSwing,
			float limbSwingAmount,
			float ageInTicks,
			float netHeadYaw,
			float headPitch) {
		ArenaFighterEquipmentVisuals.Hands hands = ArenaFighterEquipmentVisuals.handsFor(entity);
		this.rightArmPose = armPoseFor(hands.mainHand());
		this.leftArmPose = armPoseFor(hands.offHand());

		super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
	}

	private static HumanoidModel.ArmPose armPoseFor(ItemStack stack) {
		return stack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
	}
}
