package com.nikita.arenaofnations.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.nikita.arenaofnations.ArenaBaseFlagVisibility;
import com.nikita.arenaofnations.ArenaCountryBaseLayout;
import com.nikita.arenaofnations.ArenaHudCountryState;
import com.nikita.arenaofnations.ArenaHudSnapshot;
import com.nikita.arenaofnations.Country;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Large world-space markers above active country bases (flag + name + core HP + status).
 * Billboarded like fighter overhead flags; uses FULL_BRIGHT textured quads.
 */
public final class ArenaBaseMarkerRenderer {
	private static final float FLAG_HALF_W = 2.5F;
	private static final float FLAG_HALF_H = 1.5F;
	private static final float BORDER = 0.08F;
	private static final float HP_BAR_HALF_W = 2.7F;
	private static final float HP_BAR_HALF_H = 0.18F;
	private static final float TEXT_SCALE = 0.045F;
	private static final float CODE_ONLY_DISTANCE = 85.0F;
	private static final int MARKER_HEIGHT_ABOVE_CORE = 11;
	private static final int MARKER_INWARD = 2;

	private ArenaBaseMarkerRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(ArenaBaseMarkerRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		if (!ArenaBaseMarkerSettings.isEnabled()) {
			ArenaBaseMarkerSettings.recordFrame(0, 0, List.of());
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null || context.camera() == null) {
			return;
		}

		ArenaHudSnapshot snapshot = ArenaRoundHudClient.getSnapshotIfFresh();
		if (!snapshot.shouldDisplay() || !snapshot.arenaCenterValid() || snapshot.countries().isEmpty()) {
			ArenaBaseMarkerSettings.recordFrame(0, 0, List.of());
			return;
		}

		BlockPos arenaCenter = new BlockPos(snapshot.arenaCenterX(), snapshot.arenaCenterY(), snapshot.arenaCenterZ());
		Vec3 cameraPos = context.camera().getPosition();
		PoseStack poseStack = context.matrixStack();
		MultiBufferSource buffer = context.consumers() != null
				? context.consumers()
				: minecraft.renderBuffers().bufferSource();
		Font font = minecraft.font;
		Quaternionf cameraOrientation = new Quaternionf(context.camera().rotation());

		int active = 0;
		int rendered = 0;
		float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
		long gameTime = minecraft.level.getGameTime();
		List<ArenaBaseMarkerSettings.MarkerDiag> diags = new ArrayList<>();

		for (ArenaHudCountryState row : snapshot.countries()) {
			if (!ArenaBaseFlagVisibility.shouldShow(row)) {
				if (diags.size() < 5) {
					diags.add(diagHidden(row, ArenaBaseFlagVisibility.hideReason(row)));
				}
				continue;
			}
			active++;
			BlockPos core = ArenaCountryBaseLayout.visualCorePosition(arenaCenter, row.baseSlot());
			Direction inward = ArenaCountryBaseLayout.inwardDirection(row.baseSlot());
			BlockPos markerPos = core.relative(inward, MARKER_INWARD).above(MARKER_HEIGHT_ABOVE_CORE);

			double mx = markerPos.getX() + 0.5D;
			double my = markerPos.getY() + 0.2D;
			double mz = markerPos.getZ() + 0.5D;
			double dist = cameraPos.distanceTo(new Vec3(mx, my, mz));
			ResourceLocation texture = ArenaBaseFlagTextures.texture(row.country());
			boolean missing = ArenaBaseFlagTextures.isMissing(row.country());

			if (dist > ArenaBaseMarkerSettings.MAX_RENDER_DISTANCE) {
				if (diags.size() < 5) {
					diags.add(diag(row, texture, markerPos, dist, false, false, false, "distance"));
				}
				continue;
			}

			float alpha = 1.0F;
			if (dist > ArenaBaseMarkerSettings.FADE_START_DISTANCE) {
				alpha = (float) ((ArenaBaseMarkerSettings.MAX_RENDER_DISTANCE - dist)
						/ (ArenaBaseMarkerSettings.MAX_RENDER_DISTANCE - ArenaBaseMarkerSettings.FADE_START_DISTANCE));
				alpha = Mth.clamp(alpha, 0.0F, 1.0F);
			}
			if (alpha < 0.04F) {
				if (diags.size() < 5) {
					diags.add(diag(row, texture, markerPos, dist, false, false, false, "fade"));
				}
				continue;
			}

			poseStack.pushPose();
			poseStack.translate(mx - cameraPos.x, my - cameraPos.y, mz - cameraPos.z);
			poseStack.mulPose(cameraOrientation);
			// Match entity name-tag / fighter overhead convention: face camera, Y grows downward in local space.
			poseStack.scale(-1.0F, -1.0F, 1.0F);

			int light = LightTexture.FULL_BRIGHT;
			renderFlag(poseStack, buffer, row.country(), missing, alpha, light);
			renderLabels(poseStack, buffer, font, row, dist, alpha, gameTime + partial, light);
			poseStack.popPose();

			rendered++;
			if (diags.size() < 5) {
				diags.add(diag(row, texture, markerPos, dist, true, true, true, "none"));
			}
		}

		ArenaBaseMarkerSettings.recordFrame(active, rendered, diags);
	}

	private static ArenaBaseMarkerSettings.MarkerDiag diagHidden(ArenaHudCountryState row, String reason) {
		return new ArenaBaseMarkerSettings.MarkerDiag(
				row.country().getId(),
				ArenaBaseFlagTextures.texture(row.country()).toString(),
				"slot=" + row.baseSlot(),
				-1.0D,
				Math.round(row.coreHealth()),
				Math.round(row.coreMaxHealth()),
				statusText(row),
				false,
				false,
				false,
				reason);
	}

	private static ArenaBaseMarkerSettings.MarkerDiag diag(
			ArenaHudCountryState row,
			ResourceLocation texture,
			BlockPos pos,
			double dist,
			boolean flag,
			boolean text,
			boolean hp,
			String hideReason) {
		return new ArenaBaseMarkerSettings.MarkerDiag(
				row.country().getId(),
				texture.toString(),
				pos.getX() + "," + pos.getY() + "," + pos.getZ(),
				dist,
				Math.round(row.coreHealth()),
				Math.round(row.coreMaxHealth()),
				statusText(row),
				flag,
				text,
				hp,
				hideReason);
	}

	private static void renderFlag(
			PoseStack poseStack,
			MultiBufferSource buffer,
			Country country,
			boolean missing,
			float alpha,
			int light) {
		// Local space after scale(-1,-1,1): -Y is visually up, +Y is visually down.
		float top = -FLAG_HALF_H;
		float bottom = FLAG_HALF_H;
		int overlay = OverlayTexture.NO_OVERLAY;
		int a = Math.round(alpha * 255.0F);

		PoseStack.Pose pose = poseStack.last();

		// Thin dark border BEHIND the flag (negative Z = away from camera after billboard).
		VertexConsumer border = buffer.getBuffer(RenderType.entityTranslucent(ArenaBaseFlagTextures.WHITE_PIXEL));
		blitColoredQuad(
				pose,
				border,
				-FLAG_HALF_W - BORDER,
				top - BORDER,
				FLAG_HALF_W + BORDER,
				bottom + BORDER,
				-0.02F,
				18,
				18,
				18,
				Math.round(alpha * 200.0F),
				light,
				overlay);

		ResourceLocation texture = ArenaBaseFlagTextures.texture(country);
		int tr = 255;
		int tg = missing ? 0 : 255;
		int tb = missing ? 255 : 255;
		// entityTranslucent + NoCull path: textured full-bright quad, drawn after border so it is on top.
		RenderType flagType = RenderType.entityCutoutNoCull(texture);
		VertexConsumer flag = buffer.getBuffer(flagType);
		blitTexturedQuad(pose, flag, -FLAG_HALF_W, top, FLAG_HALF_W, bottom, 0.0F, tr, tg, tb, a, light, overlay);
		// Back face (double-sided without relying solely on cull state).
		blitTexturedQuadBack(pose, flag, -FLAG_HALF_W, top, FLAG_HALF_W, bottom, 0.001F, tr, tg, tb, a, light, overlay);
	}

	private static void renderLabels(
			PoseStack poseStack,
			MultiBufferSource buffer,
			Font font,
			ArenaHudCountryState row,
			double dist,
			float alpha,
			float anim,
			int light) {
		Country country = row.country();
		boolean codeOnly = dist > CODE_ONLY_DISTANCE;
		String title = codeOnly
				? country.getCode()
				: country.getDisplayName().toUpperCase(Locale.ROOT) + " · " + country.getCode();
		String status = statusText(row);
		int coreHp = Math.round(row.coreHealth());
		int coreMax = Math.max(1, Math.round(row.coreMaxHealth()));
		float fraction = Mth.clamp(row.coreHealth() / Math.max(1.0F, row.coreMaxHealth()), 0.0F, 1.0F);
		String hpText = "ЯДРО " + coreHp + " / " + coreMax;

		// After Y flip: larger +Y is visually below the flag.
		float cursorY = FLAG_HALF_H + 0.40F;
		drawCenteredText(poseStack, buffer, font, title, cursorY, 0xFFFFFF, alpha, light, true);
		cursorY += 0.55F;
		drawCenteredText(poseStack, buffer, font, hpText, cursorY, hpColorRgb(row, fraction), alpha, light, true);
		cursorY += 0.42F;
		drawHpBar(poseStack, buffer, fraction, row, anim, alpha, cursorY, light);
		cursorY += 0.55F;
		drawCenteredText(poseStack, buffer, font, status, cursorY, statusColorRgb(row), alpha, light, true);
	}

	private static void drawHpBar(
			PoseStack poseStack,
			MultiBufferSource buffer,
			float fraction,
			ArenaHudCountryState row,
			float anim,
			float alpha,
			float centerY,
			int light) {
		PoseStack.Pose pose = poseStack.last();
		int overlay = OverlayTexture.NO_OVERLAY;
		int aBg = Math.round(alpha * 200.0F);
		int aFill = Math.round(alpha * 240.0F);
		VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(ArenaBaseFlagTextures.WHITE_PIXEL));

		float top = centerY - HP_BAR_HALF_H;
		float bottom = centerY + HP_BAR_HALF_H;
		blitColoredQuad(pose, consumer, -HP_BAR_HALF_W, top, HP_BAR_HALF_W, bottom, 0.0F, 12, 12, 12, aBg, light, overlay);

		float fillRight = -HP_BAR_HALF_W + (HP_BAR_HALF_W * 2.0F) * fraction;
		int rgb = hpColorRgb(row, fraction);
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (row.rescuing()) {
			float pulse = 0.75F + 0.25F * Mth.sin(anim * 0.25F);
			aFill = Math.round(aFill * pulse);
		}
		if (fraction > 0.01F) {
			blitColoredQuad(
					pose,
					consumer,
					-HP_BAR_HALF_W,
					top + 0.03F,
					fillRight,
					bottom - 0.03F,
					0.01F,
					r,
					g,
					b,
					aFill,
					light,
					overlay);
		}
	}

	private static void drawCenteredText(
			PoseStack poseStack,
			MultiBufferSource buffer,
			Font font,
			String text,
			float y,
			int rgb,
			float alpha,
			int light,
			boolean shadow) {
		int a = Math.round(alpha * 255.0F) << 24;
		int argb = (rgb & 0x00FFFFFF) | a;

		poseStack.pushPose();
		poseStack.translate(0.0D, y, 0.02D);
		poseStack.scale(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE);
		Matrix4f matrix = poseStack.last().pose();
		float x = -font.width(text) * 0.5F;

		// Compact dark backdrop via font background; depth-tested NORMAL mode (not SEE_THROUGH).
		font.drawInBatch(
				text,
				x,
				0.0F,
				argb,
				shadow,
				matrix,
				buffer,
				Font.DisplayMode.NORMAL,
				0x88000000,
				light);
		poseStack.popPose();
	}

	private static String statusText(ArenaHudCountryState row) {
		if (row.eliminated()) {
			return "ВЫБЫЛА";
		}
		if (row.rescuing()) {
			return "СПАСЕНИЕ " + row.rescueSecondsRemaining() + "с";
		}
		if (row.coreHealth() <= 0.0F) {
			return "ЯДРО СБИТО";
		}
		if (row.coreProtected()) {
			return "ЩИТ";
		}
		return "УЯЗВИМА";
	}

	private static int statusColorRgb(ArenaHudCountryState row) {
		if (row.eliminated()) {
			return 0xAAAAAA;
		}
		if (row.rescuing()) {
			return 0xFFDD33;
		}
		if (row.coreProtected()) {
			return 0x66CCFF;
		}
		return 0xFF5555;
	}

	private static int hpColorRgb(ArenaHudCountryState row, float fraction) {
		if (row.eliminated()) {
			return 0x888888;
		}
		if (row.rescuing()) {
			return 0xFB923C;
		}
		int percent = Math.round(fraction * 100.0F);
		if (percent > 60) {
			return 0x55FF55;
		}
		if (percent >= 30) {
			return 0xFFDD33;
		}
		return 0xFF4444;
	}

	/** Same winding / UV as working fighter overhead flags. */
	private static void blitTexturedQuad(
			PoseStack.Pose pose,
			VertexConsumer consumer,
			float left,
			float top,
			float right,
			float bottom,
			float z,
			int r,
			int g,
			int b,
			int a,
			int light,
			int overlay) {
		vertex(consumer, pose, left, bottom, z, 0.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, right, bottom, z, 1.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, right, top, z, 1.0F, 0.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, left, top, z, 0.0F, 0.0F, r, g, b, a, light, overlay);
	}

	private static void blitTexturedQuadBack(
			PoseStack.Pose pose,
			VertexConsumer consumer,
			float left,
			float top,
			float right,
			float bottom,
			float z,
			int r,
			int g,
			int b,
			int a,
			int light,
			int overlay) {
		vertex(consumer, pose, right, bottom, z, 0.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, left, bottom, z, 1.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, left, top, z, 1.0F, 0.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, right, top, z, 0.0F, 0.0F, r, g, b, a, light, overlay);
	}

	private static void blitColoredQuad(
			PoseStack.Pose pose,
			VertexConsumer consumer,
			float left,
			float top,
			float right,
			float bottom,
			float z,
			int r,
			int g,
			int b,
			int a,
			int light,
			int overlay) {
		vertex(consumer, pose, left, bottom, z, 0.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, right, bottom, z, 1.0F, 1.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, right, top, z, 1.0F, 0.0F, r, g, b, a, light, overlay);
		vertex(consumer, pose, left, top, z, 0.0F, 0.0F, r, g, b, a, light, overlay);
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
