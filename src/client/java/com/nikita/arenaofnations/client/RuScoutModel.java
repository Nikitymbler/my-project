package com.nikita.arenaofnations.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Russia Scout fighter model generated from
 * model/ru_scout_reference_final_v2_refined.bbmodel.
 */
public class RuScoutModel extends EntityModel<ArenaFighterEntity> {
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
			ArenaOfNations.id("ru_scout"),
			"main");

	private final ModelPart head;
	private final ModelPart body;
	private final ModelPart rightArm;
	private final ModelPart leftArm;
	private final ModelPart rightLeg;
	private final ModelPart leftLeg;

	public RuScoutModel(ModelPart root) {
		this.head = root.getChild("head");
		this.body = root.getChild("body");
		this.rightArm = root.getChild("right_arm");
		this.leftArm = root.getChild("left_arm");
		this.rightLeg = root.getChild("right_leg");
		this.leftLeg = root.getChild("left_leg");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition head = partdefinition.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
		head.addOrReplaceChild("head_base", CubeListBuilder.create().texOffs(2, 2).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		head.addOrReplaceChild("hood_outer", CubeListBuilder.create().texOffs(36, 2).addBox(-4.5F, -8.5F, -4.5F, 9.0F, 9.0F, 9.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(74, 2).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("neck_scarf", CubeListBuilder.create().texOffs(100, 2).addBox(-4.2F, -0.2F, -2.2F, 8.4F, 2.0F, 4.4F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("cuirass", CubeListBuilder.create().texOffs(130, 2).addBox(-3.5F, 2.0F, -2.75F, 7.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("rivet_top_left", CubeListBuilder.create().texOffs(148, 2).addBox(-3.0F, 2.9F, -3.18F, 1.0F, 1.0F, 0.4F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("rivet_top_right", CubeListBuilder.create().texOffs(154, 2).addBox(2.0F, 2.9F, -3.18F, 1.0F, 1.0F, 0.4F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("rivet_bottom_left", CubeListBuilder.create().texOffs(160, 2).addBox(-3.0F, 7.3F, -3.18F, 1.0F, 1.0F, 0.4F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("rivet_bottom_right", CubeListBuilder.create().texOffs(166, 2).addBox(2.0F, 7.3F, -3.18F, 1.0F, 1.0F, 0.4F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("flag_patch", CubeListBuilder.create().texOffs(4, 72).addBox(-1.6F, 4.0F, -3.35F, 3.2F, 3.0F, 0.5F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("belt", CubeListBuilder.create().texOffs(182, 2).addBox(-4.4F, 10.5F, -2.4F, 8.8F, 2.1F, 4.8F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("belt_buckle", CubeListBuilder.create().texOffs(212, 2).addBox(-1.0F, 10.8F, -2.9F, 2.0F, 2.0F, 0.5F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("leather_skirt", CubeListBuilder.create().texOffs(222, 2).addBox(-4.5F, 11.5F, -2.5F, 9.0F, 3.8F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		body.addOrReplaceChild("chest_tab", CubeListBuilder.create().texOffs(16, 72).addBox(-1.5F, 1.0F, -3.0F, 3.0F, 2.0F, 0.55F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		body.addOrReplaceChild("cape", CubeListBuilder.create().texOffs(206, 22).addBox(-4.0F, -1.0F, -0.35F, 8.0F, 16.5F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 2.0F, 2.5F));

		PartDefinition rightArm = partdefinition.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
		rightArm.addOrReplaceChild("right_arm_base", CubeListBuilder.create().texOffs(2, 22).addBox(-3.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		rightArm.addOrReplaceChild("right_shoulder_pad", CubeListBuilder.create().texOffs(20, 22).addBox(-3.5F, -1.0F, -2.5F, 5.0F, 4.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		rightArm.addOrReplaceChild("right_bracer", CubeListBuilder.create().texOffs(42, 22).addBox(-3.3F, 5.6F, -2.3F, 4.6F, 6.4F, 4.6F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition spear = rightArm.addOrReplaceChild("spear", CubeListBuilder.create(), PartPose.offset(-1.0F, 8.0F, -2.15F));
		spear.addOrReplaceChild("spear_shaft", CubeListBuilder.create().texOffs(226, 22).addBox(-0.4F, -21.0F, -0.4F, 0.8F, 36.0F, 0.8F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		spear.addOrReplaceChild("spear_socket", CubeListBuilder.create().texOffs(232, 22).addBox(-0.8F, -20.5F, -0.8F, 1.6F, 2.0F, 1.6F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		PartDefinition spearTip = spear.addOrReplaceChild("spear_tip", CubeListBuilder.create(), PartPose.offset(0.0F, -22.0F, 0.0F));
		spearTip.addOrReplaceChild("spear_tip_core", CubeListBuilder.create().texOffs(242, 22).addBox(-0.3F, -3.5F, -0.5F, 0.6F, 5.5F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		spearTip.addOrReplaceChild("spear_blade_left", CubeListBuilder.create().texOffs(248, 22).addBox(-1.3F, -0.4F, -0.5F, 1.4F, 1.4F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, ((float) Math.PI / 4.0F)));
		spearTip.addOrReplaceChild("spear_blade_right", CubeListBuilder.create().texOffs(2, 61).addBox(-0.1F, -0.4F, -0.5F, 1.4F, 1.4F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -((float) Math.PI / 4.0F)));

		PartDefinition leftArm = partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
		leftArm.addOrReplaceChild("left_arm_base", CubeListBuilder.create().texOffs(64, 22).addBox(-1.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		leftArm.addOrReplaceChild("left_shoulder_pad", CubeListBuilder.create().texOffs(82, 22).addBox(-1.5F, -1.0F, -2.5F, 5.0F, 4.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		leftArm.addOrReplaceChild("left_bracer", CubeListBuilder.create().texOffs(104, 22).addBox(-1.3F, 5.6F, -2.3F, 4.6F, 6.4F, 4.6F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition rightLeg = partdefinition.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-2.0F, 12.0F, 0.0F));
		rightLeg.addOrReplaceChild("right_leg_base", CubeListBuilder.create().texOffs(126, 22).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		rightLeg.addOrReplaceChild("right_boot", CubeListBuilder.create().texOffs(144, 22).addBox(-2.25F, 5.8F, -2.25F, 4.5F, 6.2F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition leftLeg = partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(2.0F, 12.0F, 0.0F));
		leftLeg.addOrReplaceChild("left_leg_base", CubeListBuilder.create().texOffs(166, 22).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));
		leftLeg.addOrReplaceChild("left_boot", CubeListBuilder.create().texOffs(184, 22).addBox(-2.25F, 5.8F, -2.25F, 4.5F, 6.2F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		return LayerDefinition.create(meshdefinition, 256, 256);
	}

	@Override
	public void setupAnim(
			ArenaFighterEntity entity,
			float limbSwing,
			float limbSwingAmount,
			float ageInTicks,
			float netHeadYaw,
			float headPitch) {
		head.resetPose();
		body.resetPose();
		rightArm.resetPose();
		leftArm.resetPose();
		rightLeg.resetPose();
		leftLeg.resetPose();

		head.yRot = netHeadYaw * ((float) Math.PI / 180.0F);
		head.xRot = headPitch * ((float) Math.PI / 180.0F);

		float rightLegSwing = Mth.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount;
		float leftLegSwing = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * 1.4F * limbSwingAmount;
		rightLeg.xRot = rightLegSwing;
		leftLeg.xRot = leftLegSwing;
		// Opposite arm/leg on the same side; spear is parented under right_arm.
		rightArm.xRot = leftLegSwing;
		leftArm.xRot = rightLegSwing;

		applySpearThrustAttack();
	}

	/**
	 * Client-only spear thrust driven by {@link #attackTime}
	 * (set from {@code LivingEntity.getAttackAnim} by the entity renderer).
	 * Phases: wind-up → thrust → recover within the existing swing duration.
	 */
	private void applySpearThrustAttack() {
		float t = this.attackTime;
		if (t <= 0.0F) {
			return;
		}

		final float windupEnd = 0.22F;
		final float thrustEnd = 0.48F;

		float targetX;
		float targetY;
		float targetZ;
		float bodyTwist;
		float leftCounter;

		if (t < windupEnd) {
			// Prepare: pull the spear arm slightly back.
			float p = easeOutSin(t / windupEnd);
			targetX = Mth.lerp(p, 0.0F, 0.62F);
			targetY = Mth.lerp(p, 0.0F, -0.12F);
			targetZ = Mth.lerp(p, 0.0F, 0.08F);
			bodyTwist = Mth.lerp(p, 0.0F, -0.08F);
			leftCounter = Mth.lerp(p, 0.0F, 0.28F);
		} else if (t < thrustEnd) {
			// Thrust: snap toward a near-horizontal forward spear pose.
			float p = easeOutSin((t - windupEnd) / (thrustEnd - windupEnd));
			targetX = Mth.lerp(p, 0.62F, -1.45F);
			targetY = Mth.lerp(p, -0.12F, -0.20F);
			targetZ = Mth.lerp(p, 0.08F, 0.10F);
			bodyTwist = Mth.lerp(p, -0.08F, -0.14F);
			leftCounter = Mth.lerp(p, 0.28F, 0.42F);
		} else {
			// Recover: ease back into the walk pose.
			float p = easeInOutSin((t - thrustEnd) / (1.0F - thrustEnd));
			targetX = Mth.lerp(p, -1.45F, 0.0F);
			targetY = Mth.lerp(p, -0.20F, 0.0F);
			targetZ = Mth.lerp(p, 0.10F, 0.0F);
			bodyTwist = Mth.lerp(p, -0.14F, 0.0F);
			leftCounter = Mth.lerp(p, 0.42F, 0.0F);
		}

		// Keep a touch of walk on the arms outside the peak of the thrust.
		float attackBlend;
		if (t < windupEnd) {
			attackBlend = easeOutSin(t / windupEnd);
		} else if (t < 0.78F) {
			attackBlend = 1.0F;
		} else {
			attackBlend = 1.0F - easeInOutSin((t - 0.78F) / 0.22F);
		}

		rightArm.xRot = Mth.lerp(attackBlend, rightArm.xRot, targetX);
		rightArm.yRot = Mth.lerp(attackBlend, rightArm.yRot, targetY);
		rightArm.zRot = Mth.lerp(attackBlend, rightArm.zRot, targetZ);
		leftArm.xRot = Mth.lerp(attackBlend, leftArm.xRot, leftArm.xRot + leftCounter);
		body.yRot = Mth.lerp(attackBlend, body.yRot, bodyTwist);
	}

	private static float easeOutSin(float value) {
		float clamped = Mth.clamp(value, 0.0F, 1.0F);
		return Mth.sin(clamped * ((float) Math.PI / 2.0F));
	}

	private static float easeInOutSin(float value) {
		float clamped = Mth.clamp(value, 0.0F, 1.0F);
		return 0.5F - 0.5F * Mth.cos(clamped * (float) Math.PI);
	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
		head.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		rightArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		leftArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		rightLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		leftLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
	}
}
