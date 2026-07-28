"""Generate RuScoutModel.java from Blockbench .bbmodel (modded_entity)."""
from __future__ import annotations

import json
import math
from pathlib import Path

BB = Path(r"D:\Minecraft\ArenaOfNations\model\ru_scout_reference_final_v2_refined.bbmodel")
OUT = Path(r"D:\Minecraft\ArenaOfNations\src\client\java\com\nikita\arenaofnations\client\RuScoutModel.java")

data = json.loads(BB.read_text(encoding="utf-8"))
els = {e["name"]: e for e in data["elements"]}
tex_w = int(data["resolution"]["width"])
tex_h = int(data["resolution"]["height"])


def f(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    if "." not in s:
        s += ".0"
    return s + "F"


def cube_box(el: dict) -> tuple[list[float], list[float], list[float]]:
    origin = el["origin"]
    fr, to = el["from"], el["to"]
    pos = [
        fr[0] - origin[0],
        origin[1] - to[1],
        fr[2] - origin[2],
    ]
    size = [to[0] - fr[0], to[1] - fr[1], to[2] - fr[2]]
    return origin, pos, size


def part_offset(origin: list[float], parent_origin: list[float] | None = None) -> list[float]:
    if parent_origin is None:
        # Root-level bone: map Blockbench feet-space origin into MC entity space.
        return [origin[0], 24.0 - origin[1], origin[2]]
    return [
        origin[0] - parent_origin[0],
        parent_origin[1] - origin[1],
        origin[2] - parent_origin[2],
    ]


def uv_offs(el: dict) -> tuple[int, int]:
    u, v = el.get("uv_offset", [0, 0])
    return int(round(u)), int(round(v))


def add_cube_lines(el_name: str, indent: str = "\t\t") -> str:
    el = els[el_name]
    _, pos, size = cube_box(el)
    u, v = uv_offs(el)
    return (
        f"{indent}.texOffs({u}, {v})"
        f".addBox({f(pos[0])}, {f(pos[1])}, {f(pos[2])}, "
        f"{f(size[0])}, {f(size[1])}, {f(size[2])}, new CubeDeformation(0.0F))"
    )


def deg_to_rad_expr(deg: float) -> str:
    if abs(deg) < 1e-6:
        return "0.0F"
    rad = math.radians(deg)
    # Prefer readable constants for ±45
    if abs(abs(deg) - 45.0) < 1e-4:
        sign = "-" if deg < 0 else ""
        return f"{sign}((float) Math.PI / 4.0F)"
    return f"{f(rad)}"


# Bone origins from Blockbench
HEAD_O = [0.0, 24.0, 0.0]
BODY_O = [0.0, 24.0, 0.0]
RARM_O = [-5.0, 22.0, 0.0]
LARM_O = [5.0, 22.0, 0.0]
RLEG_O = [-2.0, 12.0, 0.0]
LLEG_O = [2.0, 12.0, 0.0]
CAPE_O = [0.0, 22.0, 2.5]
SPEAR_O = [-6.0, 14.0, -2.15]
SPEAR_TIP_O = [-6.0, 36.0, -2.15]

head_off = part_offset(HEAD_O)
body_off = part_offset(BODY_O)
rarm_off = part_offset(RARM_O)
larm_off = part_offset(LARM_O)
rleg_off = part_offset(RLEG_O)
lleg_off = part_offset(LLEG_O)
cape_off = part_offset(CAPE_O, BODY_O)
spear_off = part_offset(SPEAR_O, RARM_O)
spear_tip_off = part_offset(SPEAR_TIP_O, SPEAR_O)

# Cubes that share bone origins (as sibling child parts for clean hierarchy)
HEAD_PARTS = ["head_base", "hood_outer"]
BODY_PARTS = [
    "torso",
    "neck_scarf",
    "cuirass",
    "rivet_top_left",
    "rivet_top_right",
    "rivet_bottom_left",
    "rivet_bottom_right",
    "flag_patch",
    "belt",
    "belt_buckle",
    "leather_skirt",
    "chest_tab",
]
RARM_PARTS = ["right_arm_base", "right_shoulder_pad", "right_bracer"]
LARM_PARTS = ["left_arm_base", "left_shoulder_pad", "left_bracer"]
RLEG_PARTS = ["right_leg_base", "right_boot"]
LLEG_PARTS = ["left_leg_base", "left_boot"]
SPEAR_PARTS = ["spear_shaft", "spear_socket"]
SPEAR_TIP_PARTS = ["spear_tip_core"]
# rotated blades as separate children of spear_tip


def child_cube_part(parent_var: str, el_name: str, bone_origin: list[float], indent: str = "\t\t") -> str:
    el = els[el_name]
    origin, pos, size = cube_box(el)
    # Child offset relative to bone
    off = part_offset(origin, bone_origin)
    u, v = uv_offs(el)
    rot = el.get("rotation") or [0, 0, 0]
    lines = []
    if any(abs(r) > 1e-6 for r in rot):
        # Blockbench degrees; with Y-flip use negated X/Y (Blockbench Java entity convention)
        rx = deg_to_rad_expr(-rot[0])
        ry = deg_to_rad_expr(-rot[1])
        rz = deg_to_rad_expr(rot[2])
        pose = (
            f"PartPose.offsetAndRotation({f(off[0])}, {f(off[1])}, {f(off[2])}, "
            f"{rx}, {ry}, {rz})"
        )
    else:
        pose = f"PartPose.offset({f(off[0])}, {f(off[1])}, {f(off[2])})"
    lines.append(
        f"{indent}{parent_var}.addOrReplaceChild(\"{el_name}\", "
        f"CubeListBuilder.create().texOffs({u}, {v})"
        f".addBox({f(pos[0])}, {f(pos[1])}, {f(pos[2])}, "
        f"{f(size[0])}, {f(size[1])}, {f(size[2])}, new CubeDeformation(0.0F)), "
        f"{pose});"
    )
    return "\n".join(lines)


def multi_cube_bone(var: str, name: str, bone_origin: list[float], part_names: list[str], root_off: list[float]) -> str:
    """Create a bone with empty pose and attach each cube as a child part (preserves names)."""
    lines = [
        f"\t\tPartDefinition {var} = partdefinition.addOrReplaceChild(\"{name}\", "
        f"CubeListBuilder.create(), "
        f"PartPose.offset({f(root_off[0])}, {f(root_off[1])}, {f(root_off[2])}));"
    ]
    for pn in part_names:
        lines.append(child_cube_part(var, pn, bone_origin))
    return "\n".join(lines)


java = []
java.append("""package com.nikita.arenaofnations.client;

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
""")

java.append(multi_cube_bone("head", "head", HEAD_O, HEAD_PARTS, head_off))
java.append("")
java.append(multi_cube_bone("body", "body", BODY_O, BODY_PARTS, body_off))
java.append("")
# cape child of body
java.append(child_cube_part("body", "cape", BODY_O).replace(
    # cape has its own origin; child_cube_part already uses el origin vs BODY_O
    "", ""
))
# Fix: child_cube_part for cape uses cape's origin relative to BODY_O - good.
# But the cube pos is relative to cape origin - and PartPose is cape origin relative to body.
# Wait - child_cube_part creates a part named "cape" with offset = cape origin vs body,
# and cube box relative to cape origin. Correct!

java.append("")
java.append(multi_cube_bone("rightArm", "right_arm", RARM_O, RARM_PARTS, rarm_off))
java.append("")
# spear group under right arm
java.append(
    f"\t\tPartDefinition spear = rightArm.addOrReplaceChild(\"spear\", "
    f"CubeListBuilder.create(), "
    f"PartPose.offset({f(spear_off[0])}, {f(spear_off[1])}, {f(spear_off[2])}));"
)
for pn in SPEAR_PARTS:
    java.append(child_cube_part("spear", pn, SPEAR_O))

java.append(
    f"\t\tPartDefinition spearTip = spear.addOrReplaceChild(\"spear_tip\", "
    f"CubeListBuilder.create(), "
    f"PartPose.offset({f(spear_tip_off[0])}, {f(spear_tip_off[1])}, {f(spear_tip_off[2])}));"
)
for pn in SPEAR_TIP_PARTS:
    java.append(child_cube_part("spearTip", pn, SPEAR_TIP_O))
java.append(child_cube_part("spearTip", "spear_blade_left", SPEAR_TIP_O))
java.append(child_cube_part("spearTip", "spear_blade_right", SPEAR_TIP_O))

java.append("")
java.append(multi_cube_bone("leftArm", "left_arm", LARM_O, LARM_PARTS, larm_off))
java.append("")
java.append(multi_cube_bone("rightLeg", "right_leg", RLEG_O, RLEG_PARTS, rleg_off))
java.append("")
java.append(multi_cube_bone("leftLeg", "left_leg", LLEG_O, LLEG_PARTS, lleg_off))

java.append(f"""
		return LayerDefinition.create(meshdefinition, {tex_w}, {tex_h});
	}}

	@Override
	public void setupAnim(
			ArenaFighterEntity entity,
			float limbSwing,
			float limbSwingAmount,
			float ageInTicks,
			float netHeadYaw,
			float headPitch) {{
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
	}}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {{
		head.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		rightArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		leftArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		rightLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
		leftLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
	}}
}}
""")

text = "\n".join(java)
# Clean accidental double blank issues around cape
OUT.write_text(text, encoding="utf-8")
print("Wrote", OUT)
print("head", head_off, "body", body_off, "rarm", rarm_off, "spear", spear_off, "cape", cape_off)
