package com.nikita.arenaofnations.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaOfNations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Draws country flag + optional health bar above arena fighters.
 * Single render path (called only from {@link ArenaFighterRenderer}).
 * Billboarded to the camera; uses depth-tested entity render types (not see-through).
 */
public final class ArenaFighterOverheadRenderer {
	private static final AtomicBoolean LOGGED_FIRST_DRAW = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_FIRST_HP = new AtomicBoolean(false);

	/** Flag bottom edge in local billboard space (see blitFlagQuad). */
	private static final float FLAG_BOTTOM_Y = ArenaFighterFlagVisuals.FLAG_HALF_HEIGHT;
	/** ~2px gap under the flag. */
	private static final float HP_GAP = 2.0F;
	private static final float BAR_LEFT = -14.0F;
	private static final float BAR_RIGHT = 14.0F;
	private static final float BAR_HEIGHT = 4.0F;
	private static final float BAR_BORDER = 1.0F;

	/** Depth offsets to prevent z-fighting between border / flag / HP. */
	private static final float Z_BORDER = -0.02F;
	private static final float Z_FLAG = 0.0F;
	private static final float Z_HP = 0.03F;

	private ArenaFighterOverheadRenderer() {
	}

	public static void render(
			ArenaFighterEntity entity,
			EntityRenderDispatcher dispatcher,
			PoseStack poseStack,
			MultiBufferSource buffer,
			float partialTick) {
		if (entity == null || dispatcher == null || poseStack == null || buffer == null) {
			return;
		}
		if (!entity.isAlive() || entity.isRemoved()) {
			ArenaFighterFlagVisuals.recordHideReason(ArenaFighterFlagVisuals.HIDE_DEAD);
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return;
		}
		Player localPlayer = minecraft.player;
		if (localPlayer == null) {
			return;
		}
		if (entity.isInvisibleTo(localPlayer)) {
			ArenaFighterFlagVisuals.recordHideReason(ArenaFighterFlagVisuals.HIDE_INVISIBLE);
			return;
		}

		double distSqr = dispatcher.distanceToSqr(entity);
		if (!ArenaFighterFlagVisuals.shouldShowFlag(entity, distSqr)) {
			return;
		}
		boolean showHealth = ArenaFighterFlagVisuals.shouldShowHealth(entity, distSqr);
		ArenaFighterFlagVisuals.recordHideReason(ArenaFighterFlagVisuals.HIDE_NONE);

		float scale = ArenaFighterFlagVisuals.billboardScale(entity);
		float indicatorY = ArenaFighterFlagVisuals.indicatorY(entity);
		float halfW = ArenaFighterFlagVisuals.FLAG_HALF_WIDTH;
		float halfH = ArenaFighterFlagVisuals.FLAG_HALF_HEIGHT;

		ResourceLocation flagTexture = ArenaFighterFlagVisuals.flagTexture(entity);
		boolean fallback = flagTexture.equals(ArenaFighterFlagVisuals.FALLBACK_TEXTURE);
		int flagTintR = 255;
		int flagTintG = fallback ? 0 : 255;
		int flagTintB = fallback ? 255 : 255;

		poseStack.pushPose();
		poseStack.translate(0.0D, indicatorY, 0.0D);
		// Fresh camera orientation each frame — stable billboard follow during movement.
		poseStack.mulPose(new Quaternionf(dispatcher.cameraOrientation()));
		poseStack.scale(-scale, -scale, scale);

		int light = LightTexture.FULL_BRIGHT;
		int overlay = OverlayTexture.NO_OVERLAY;

		PoseStack.Pose pose = poseStack.last();
		// Thin dark border BEHIND the flag (negative Z) — avoids z-fighting flicker.
		RenderType borderType = RenderType.entityTranslucent(ArenaFighterFlagVisuals.WHITE_PIXEL);
		VertexConsumer borderConsumer = buffer.getBuffer(borderType);
		float border = 0.6F;
		drawColoredQuad(
				pose,
				borderConsumer,
				-halfW - border,
				-halfH - border,
				halfW + border,
				halfH + border,
				Z_BORDER,
				15,
				15,
				15,
				180);

		RenderType flagType = RenderType.entityCutoutNoCull(flagTexture);
		VertexConsumer flagConsumer = buffer.getBuffer(flagType);
		blitFlagQuad(
				poseStack,
				flagConsumer,
				-halfW,
				-halfH,
				halfW,
				halfH,
				Z_FLAG,
				flagTintR,
				flagTintG,
				flagTintB,
				light,
				overlay);

		if (showHealth) {
			renderHealthBar(entity, poseStack, buffer, distSqr);
		}

		if (LOGGED_FIRST_DRAW.compareAndSet(false, true)) {
			float maxHealth = entity.getMaxHealth();
			float health = entity.getHealth();
			float healthFraction = maxHealth > 0.0F ? Mth.clamp(health / maxHealth, 0.0F, 1.0F) : 0.0F;
			ArenaOfNations.LOGGER.info(
					"ArenaFighterOverheadRenderer: first draw — country={}, texture={}, renderType={}, scale={}, indicatorY={}, half={}x{}, distSqr={}, showHealth={}, health={}/{}, fraction={}, paths={}",
					entity.getArenaCountry(),
					flagTexture,
					flagType,
					scale,
					indicatorY,
					halfW * 2.0F,
					halfH * 2.0F,
					distSqr,
					showHealth,
					health,
					maxHealth,
					healthFraction,
					ArenaFighterFlagVisuals.FIGHTER_FLAG_RENDER_PATH_COUNT);
			ArenaFighterFlagVisuals.logOnce(entity, flagTexture, scale, indicatorY);
		}

		poseStack.popPose();
	}

	/**
	 * Textured flag quad with normalized UVs.
	 * Local Y grows downward after scale(-s,-s,s); top of flag is -halfH.
	 * U is flipped vs texture file so hoist (left in PNG) stays on the viewer's left
	 * after the billboard {@code scale(-s,-s,s)} mirror — same visual as base markers.
	 */
	private static void blitFlagQuad(
			PoseStack poseStack,
			VertexConsumer consumer,
			float left,
			float top,
			float right,
			float bottom,
			float z,
			int r,
			int g,
			int b,
			int light,
			int overlay) {
		PoseStack.Pose pose = poseStack.last();
		vertex(consumer, pose, left, bottom, z, 1.0F, 1.0F, r, g, b, 255, light, overlay);
		vertex(consumer, pose, right, bottom, z, 0.0F, 1.0F, r, g, b, 255, light, overlay);
		vertex(consumer, pose, right, top, z, 0.0F, 0.0F, r, g, b, 255, light, overlay);
		vertex(consumer, pose, left, top, z, 1.0F, 0.0F, r, g, b, 255, light, overlay);
	}

	/**
	 * HP under the flag. Flag bottom is +FLAG_HALF_HEIGHT in local space; bar uses larger +Y.
	 * Drawn at Z_HP so it does not z-fight with the flag plane.
	 */
	private static void renderHealthBar(
			ArenaFighterEntity entity,
			PoseStack poseStack,
			MultiBufferSource buffer,
			double distSqr) {
		float barTop = FLAG_BOTTOM_Y + HP_GAP;
		float barBottom = barTop + BAR_HEIGHT;
		float barLeft = BAR_LEFT;
		float barRight = BAR_RIGHT;

		float innerLeft = barLeft + BAR_BORDER;
		float innerRight = barRight - BAR_BORDER;
		float innerTop = barTop + BAR_BORDER;
		float innerBottom = barBottom - BAR_BORDER;

		float maxHealth = entity.getMaxHealth();
		float health = entity.getHealth();
		float healthFraction = maxHealth > 0.0F ? Mth.clamp(health / maxHealth, 0.0F, 1.0F) : 0.0F;
		float fillRight = Mth.lerp(healthFraction, innerLeft, innerRight);

		RenderType healthType = RenderType.entityTranslucent(ArenaFighterFlagVisuals.WHITE_PIXEL);
		VertexConsumer healthConsumer = buffer.getBuffer(healthType);
		PoseStack.Pose pose = poseStack.last();

		final float z = Z_HP;

		drawColoredQuad(pose, healthConsumer, barLeft, barTop, barRight, barTop + BAR_BORDER, z, 15, 15, 15, 255);
		drawColoredQuad(pose, healthConsumer, barLeft, barBottom - BAR_BORDER, barRight, barBottom, z, 15, 15, 15, 255);
		drawColoredQuad(pose, healthConsumer, barLeft, innerTop, barLeft + BAR_BORDER, innerBottom, z, 15, 15, 15, 255);
		drawColoredQuad(pose, healthConsumer, barRight - BAR_BORDER, innerTop, barRight, innerBottom, z, 15, 15, 15, 255);

		if (fillRight > innerLeft) {
			int fr;
			int fg;
			int fb;
			if (healthFraction > 0.60F) {
				fr = 40;
				fg = 220;
				fb = 70;
			} else if (healthFraction >= 0.30F) {
				fr = 240;
				fg = 190;
				fb = 30;
			} else {
				fr = 230;
				fg = 45;
				fb = 45;
			}
			drawColoredQuad(pose, healthConsumer, innerLeft, innerTop, fillRight, innerBottom, z, fr, fg, fb, 255);
		}

		if (fillRight < innerRight) {
			drawColoredQuad(pose, healthConsumer, fillRight, innerTop, innerRight, innerBottom, z, 45, 45, 45, 230);
		}

		if (LOGGED_FIRST_HP.compareAndSet(false, true)) {
			boolean whiteExists = ArenaFighterFlagVisuals.resourceExists(ArenaFighterFlagVisuals.WHITE_PIXEL);
			ArenaOfNations.LOGGER.info(
					"ArenaFighterOverheadRenderer: first HP — country={}, tier={}, distSqr={}, showHealth=true, health={}/{}, fraction={}, barTop={}, barBottom={}, renderType={}, whitePixelExists={}",
					entity.getArenaCountry(),
					entity.getArenaTier(),
					distSqr,
					health,
					maxHealth,
					healthFraction,
					barTop,
					barBottom,
					healthType,
					whiteExists);
		}
	}

	private static void drawColoredQuad(
			PoseStack.Pose pose,
			VertexConsumer consumer,
			float left,
			float top,
			float right,
			float bottom,
			float z,
			int red,
			int green,
			int blue,
			int alpha) {
		int light = LightTexture.FULL_BRIGHT;
		int overlay = OverlayTexture.NO_OVERLAY;
		vertex(consumer, pose, left, bottom, z, 0.0F, 1.0F, red, green, blue, alpha, light, overlay);
		vertex(consumer, pose, right, bottom, z, 1.0F, 1.0F, red, green, blue, alpha, light, overlay);
		vertex(consumer, pose, right, top, z, 1.0F, 0.0F, red, green, blue, alpha, light, overlay);
		vertex(consumer, pose, left, top, z, 0.0F, 0.0F, red, green, blue, alpha, light, overlay);
	}

	private static void vertex(
			VertexConsumer consumer,
			PoseStack.Pose pose,
			float x,
			float y,
			float z,
			float u,
			float v,
			int r,
			int g,
			int b,
			int a,
			int light,
			int overlay) {
		consumer.addVertex(pose, x, y, z)
				.setColor(r, g, b, a)
				.setUv(u, v)
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, 0.0F, 0.0F, 1.0F);
	}
}
