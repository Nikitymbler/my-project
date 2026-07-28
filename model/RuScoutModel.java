// Made with Blockbench 5.1.5
// Exported for Minecraft version 1.17 or later with Mojang mappings
// Paste this class into your mod and generate all required imports


public class ru_scout_medieval_v2<T extends Entity> extends EntityModel<T> {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("modid", "ru_scout_medieval_v2"), "main");
	private final ModelPart head;
	private final ModelPart body;
	private final ModelPart right_arm;
	private final ModelPart left_arm;
	private final ModelPart right_leg;
	private final ModelPart left_leg;
	private final ModelPart cloak;
	private final ModelPart pouch;
	private final ModelPart weapon;

	public ru_scout_medieval_v2(ModelPart root) {
		this.head = root.getChild("head");
		this.body = root.getChild("body");
		this.right_arm = root.getChild("right_arm");
		this.left_arm = root.getChild("left_arm");
		this.right_leg = root.getChild("right_leg");
		this.left_leg = root.getChild("left_leg");
		this.cloak = root.getChild("cloak");
		this.pouch = root.getChild("pouch");
		this.weapon = root.getChild("weapon");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition head = partdefinition.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
		.texOffs(48, 32).addBox(-4.6F, -8.7F, -4.6F, 9.2F, 9.2F, 9.2F, new CubeDeformation(0.15F))
		.texOffs(48, 32).addBox(-4.8F, -6.1F, -4.9F, 9.6F, 2.9F, 1.1F, new CubeDeformation(0.0F))
		.texOffs(32, 0).addBox(-3.6F, -3.4F, -4.25F, 7.2F, 4.0F, 0.6F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(32, 16).addBox(-4.35F, 1.0F, -2.35F, 8.7F, 9.0F, 4.7F, new CubeDeformation(0.05F))
		.texOffs(32, 32).addBox(-4.45F, 9.8F, -2.45F, 8.9F, 2.0F, 4.9F, new CubeDeformation(0.0F))
		.texOffs(0, 48).addBox(-1.2F, 9.9F, -2.75F, 2.4F, 1.6F, 0.4F, new CubeDeformation(0.0F))
		.texOffs(0, 16).addBox(0.2F, 11.5F, -2.1F, 3.9F, 4.5F, 4.2F, new CubeDeformation(0.0F))
		.texOffs(0, 16).addBox(-4.1F, 11.5F, -2.1F, 3.9F, 4.5F, 4.2F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 0.0F));

		PartDefinition right_strap_r1 = body.addOrReplaceChild("right_strap_r1", CubeListBuilder.create().texOffs(32, 32).addBox(-2.9F, 0.7F, -2.6F, 1.2F, 8.3F, 0.35F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.3142F));

		PartDefinition left_strap_r1 = body.addOrReplaceChild("left_strap_r1", CubeListBuilder.create().texOffs(32, 32).addBox(1.7F, 0.7F, -2.6F, 1.2F, 8.3F, 0.35F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -0.3142F));

		PartDefinition right_arm = partdefinition.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(0, 16).addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(0, 32).addBox(-1.3F, -2.5F, -2.5F, 3.8F, 4.5F, 5.0F, new CubeDeformation(0.0F))
		.texOffs(32, 32).addBox(-1.15F, 5.2F, -2.25F, 3.4F, 4.7F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(5.0F, 2.0F, 0.0F));

		PartDefinition left_arm = partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(0, 16).addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(0, 32).addBox(-2.5F, -2.5F, -2.5F, 3.8F, 4.5F, 5.0F, new CubeDeformation(0.0F))
		.texOffs(32, 32).addBox(-2.25F, 5.2F, -2.25F, 3.4F, 4.7F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(-5.0F, 2.0F, 0.0F));

		PartDefinition right_leg = partdefinition.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(16, 48).addBox(-2.15F, 6.8F, -2.45F, 4.3F, 5.2F, 5.05F, new CubeDeformation(0.0F))
		.texOffs(32, 32).addBox(-2.2F, 3.7F, -2.25F, 4.4F, 3.1F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(2.0F, 12.0F, 0.0F));

		PartDefinition left_leg = partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(16, 48).addBox(-2.15F, 6.8F, -2.45F, 4.3F, 5.2F, 5.05F, new CubeDeformation(0.0F))
		.texOffs(32, 32).addBox(-2.2F, 3.7F, -2.25F, 4.4F, 3.1F, 4.5F, new CubeDeformation(0.0F)), PartPose.offset(-2.0F, 12.0F, 0.0F));

		PartDefinition cloak = partdefinition.addOrReplaceChild("cloak", CubeListBuilder.create().texOffs(0, 32).addBox(-4.6F, -0.5F, -0.3F, 9.2F, 15.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(48, 48).addBox(-5.0F, -1.7F, -4.9F, 10.0F, 3.2F, 5.5F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 1.0F, 2.5F));

		PartDefinition cloak_right_fold_r1 = cloak.addOrReplaceChild("cloak_right_fold_r1", CubeListBuilder.create().texOffs(0, 32).addBox(-4.9F, 0.5F, -0.5F, 2.1F, 14.0F, 1.4F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -0.0698F));

		PartDefinition cloak_left_fold_r1 = cloak.addOrReplaceChild("cloak_left_fold_r1", CubeListBuilder.create().texOffs(0, 32).addBox(2.8F, 0.5F, -0.5F, 2.1F, 14.0F, 1.4F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0698F));

		PartDefinition pouch = partdefinition.addOrReplaceChild("pouch", CubeListBuilder.create().texOffs(32, 32).addBox(-1.2F, -0.8F, -1.35F, 2.1F, 4.3F, 2.85F, new CubeDeformation(0.0F))
		.texOffs(32, 32).addBox(-1.35F, -1.3F, -1.6F, 2.4F, 1.6F, 3.2F, new CubeDeformation(0.0F)), PartPose.offset(-4.2F, 10.0F, -1.3F));

		PartDefinition weapon = partdefinition.addOrReplaceChild("weapon", CubeListBuilder.create().texOffs(32, 32).addBox(-0.45F, -12.0F, -0.45F, 0.9F, 25.0F, 0.9F, new CubeDeformation(0.0F))
		.texOffs(0, 48).addBox(-0.85F, -14.2F, -0.85F, 1.7F, 2.7F, 1.7F, new CubeDeformation(0.0F))
		.texOffs(0, 48).addBox(-0.55F, -18.8F, -0.55F, 1.1F, 4.8F, 1.1F, new CubeDeformation(0.0F)), PartPose.offset(6.7F, 11.0F, -0.1F));

		return LayerDefinition.create(meshdefinition, 64, 64);
	}

	@Override
	public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {

	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
		head.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		body.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		right_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		left_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		right_leg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		left_leg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		cloak.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		pouch.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		weapon.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}