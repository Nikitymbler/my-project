package com.nikita.arenaofnations.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nikita.arenaofnations.ArenaHudCountryState;
import com.nikita.arenaofnations.ArenaHudSnapshot;
import com.nikita.arenaofnations.ArenaMatchState;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;
import com.nikita.arenaofnations.CountryVisualPalette;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Adaptive round HUD v3 — 4×5 grid for 11–20 countries, compact row backgrounds.
 */
public final class ArenaRoundHudRenderer {
	private static final int FLAG_TEX_WIDTH = 64;
	private static final int FLAG_TEX_HEIGHT = 40;
	private static final AtomicBoolean LOGGED_FIRST_FLAG = new AtomicBoolean(false);

	private static final int COLOR_ELIMINATED = 0xFF888888;
	private static final int COLOR_RESCUE = 0xFFFFCC33;
	private static final int COLOR_VULN = 0xFFFF66AA;
	private static final int COLOR_SHIELD = 0xFF66EEFF;

	private ArenaRoundHudRenderer() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register((graphics, deltaTracker) -> render(graphics));
	}

	public static String debugReport(int screenWidth, int screenHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		double guiScale = minecraft != null && minecraft.getWindow() != null
				? minecraft.getWindow().getGuiScale()
				: 0.0D;
		ArenaHudLayout.Metrics m = ArenaHudLayout.lastMetrics();
		ArenaHudLayout.Bounds b = ArenaHudLayout.lastBounds();
		int active = ArenaRoundHudClient.getSnapshotIfFresh().countries().size();
		return "HUD layout debug v3:\n"
				+ "guiWidth=" + screenWidth + " guiHeight=" + screenHeight + " guiScale=" + guiScale + "\n"
				+ "activeCountries=" + active + " selectedMode=" + m.mode() + "\n"
				+ "rowsPerColumn=" + m.rowsPerColumn() + " columns=" + m.columns() + "\n"
				+ "rowHeight=" + m.rowHeight() + " columnWidth=" + m.columnWidth() + "\n"
				+ "totalBounds=" + b.left() + "," + b.top() + "—" + b.right() + "," + b.bottom() + "\n"
				+ "bottomLimit=" + m.bottomLimit() + "\n"
				+ "overlap=" + m.overlapDetected() + " clippedRows=" + m.clippedRows();
	}

	private static void render(GuiGraphics graphics) {
		ArenaHudSnapshot snapshot = ArenaRoundHudClient.getSnapshotIfFresh();
		if (!snapshot.shouldDisplay()) {
			return;
		}

		if (snapshot.mode() == com.nikita.arenaofnations.ArenaHudDisplayMode.OFF) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();

		String status = "АРЕНА · " + snapshot.formatStatusText();
		String timer = snapshot.remainingTicks() > 0 || snapshot.state() != ArenaMatchState.IDLE
				? snapshot.formatTimerMmSs()
				: "";

		int statusWidth = font.width(status);
		int statusX = (screenWidth - statusWidth) / 2;
		graphics.fill(statusX - 3, 4, statusX + statusWidth + 3, 14, 0x90000000);
		graphics.drawString(font, status, statusX, 5, stateColor(snapshot.state()), true);

		if (!timer.isEmpty()) {
			graphics.fill(screenWidth / 2 - 36, 16, screenWidth / 2 + 36, 28, 0xA0000000);
			graphics.drawString(font, timer, screenWidth / 2 - font.width(timer) / 2, 18, 0xFFFFFFFF, true);
		}

		if (snapshot.mode() == com.nikita.arenaofnations.ArenaHudDisplayMode.EXTERNAL
				|| snapshot.mode() == com.nikita.arenaofnations.ArenaHudDisplayMode.MINIMAL) {
			String minimal = "Стран: " + snapshot.activeCountryCount();
			if (snapshot.rescueCountryCode() != null) {
				minimal += " | СПАСЕНИЕ: " + snapshot.rescueCountryCode();
			}
			int minimalWidth = font.width(minimal);
			int x = (screenWidth - minimalWidth) / 2;
			int y = 31;
			graphics.fill(x - 3, y - 1, x + minimalWidth + 3, y + 10, 0x78000000);
			graphics.drawString(font, minimal, x, y, 0xFFE6E6E6, true);
			return;
		}

		List<ArenaHudCountryState> countries = new ArrayList<>(snapshot.countries());
		countries.sort((a, b) -> {
			int slotA = a.baseSlot() < 0 ? 999 : a.baseSlot();
			int slotB = b.baseSlot() < 0 ? 999 : b.baseSlot();
			if (slotA != slotB) {
				return Integer.compare(slotA, slotB);
			}
			return a.country().getCode().compareTo(b.country().getCode());
		});

		int count = countries.size();
		if (count == 0) {
			return;
		}

		ArenaHudLayout.Metrics layout = ArenaHudLayout.compute(screenWidth, screenHeight, count);
		int cardsTop = layout.startY();
		int panelWidth = layout.columns() * layout.columnWidth()
				+ (layout.columns() - 1) * layout.columnGap();
		int startX = (screenWidth - panelWidth) / 2;

		for (int index = 0; index < count; index++) {
			int column = layout.columns() == 1 ? 0 : index / layout.rowsPerColumn();
			int row = layout.columns() == 1 ? index : index % layout.rowsPerColumn();
			int x = startX + column * (layout.columnWidth() + layout.columnGap());
			int y = cardsTop + row * (layout.rowHeight() + layout.rowGap());
			if (y + layout.rowHeight() > layout.bottomLimit()) {
				continue;
			}
			renderCountryRow(graphics, font, layout, countries.get(index), x, y);
		}
	}

	private static void renderCountryRow(
			GuiGraphics graphics,
			Font font,
			ArenaHudLayout.Metrics layout,
			ArenaHudCountryState country,
			int x,
			int y) {
		boolean eliminated = country.eliminated();
		boolean rescuing = country.rescuing() && !eliminated;
		float ratio = country.coreMaxHealth() <= 0.0F ? 0.0F
				: Math.max(0.0F, Math.min(1.0F, country.coreHealth() / country.coreMaxHealth()));
		boolean coreDown = !eliminated && !rescuing && ratio <= 0.0F;
		boolean shielded = country.coreProtected() && !eliminated && !rescuing && !coreDown;
		boolean ultra = layout.mode() == ArenaHudLayout.Mode.ULTRA;
		boolean compact = layout.mode() == ArenaHudLayout.Mode.COMPACT || ultra;

		int accent = CountryVisualPalette.hudAccent(country.country());
		int bg = eliminated ? 0x70101010 : 0x70181818;
		int rowW = compact ? layout.contentWidth() : layout.columnWidth();
		graphics.fill(x, y, x + rowW, y + layout.rowHeight(), bg);

		int flagX = x + 2;
		int flagY = y + (layout.rowHeight() - layout.flagHeight()) / 2;
		drawFlag(graphics, country.country(), flagX, flagY, layout.flagWidth(), layout.flagHeight());
		if (eliminated) {
			graphics.fill(flagX, flagY, flagX + layout.flagWidth(), flagY + layout.flagHeight(), 0x88000000);
		}

		int codeX = flagX + layout.flagWidth() + 3;
		int textY = y + (layout.rowHeight() - 8) / 2;
		graphics.drawString(font, country.country().getCode(), codeX, textY,
				eliminated ? COLOR_ELIMINATED : accent, true);

		String fighters = country.aliveFighters() + "/" + country.reserveCount();
		int fightersX = codeX + layout.codeWidth();
		graphics.drawString(font, fighters, fightersX, textY,
				eliminated ? COLOR_ELIMINATED : 0xFFDDDDDD, true);

		int barX = fightersX + layout.fightersWidth();
		int barY = y + layout.rowHeight() - (compact ? 5 : 6);
		int barH = ultra ? 2 : 3;
		graphics.fill(barX, barY, barX + layout.barWidth(), barY + barH, 0xFF1A1A1A);
		if (!eliminated && !rescuing && !coreDown) {
			int fill = Math.round(layout.barWidth() * ratio);
			int hpColor = ratio > 0.60F ? 0xFF55FF55 : ratio >= 0.30F ? 0xFFFFFF55 : 0xFFFF5555;
			if (fill > 0) {
				graphics.fill(barX, barY, barX + fill, barY + barH, hpColor);
			}
		}

		int percent = Math.round(ratio * 100.0F);
		int percentX = barX + layout.barWidth() + 3;
		String percentText = compact ? Integer.toString(percent) : percent + "%";
		graphics.drawString(font, percentText, percentX, textY,
				eliminated ? COLOR_ELIMINATED : 0xFFCCCCCC, false);

		String status = statusLabel(country, eliminated, rescuing, coreDown, shielded, ultra);
		int statusColor = statusColor(country, eliminated, rescuing, coreDown, shielded);
		int statusX = x + rowW - layout.statusWidth();
		graphics.drawString(font, status, statusX, textY, statusColor, true);
	}

	private static String statusLabel(
			ArenaHudCountryState country,
			boolean eliminated,
			boolean rescuing,
			boolean coreDown,
			boolean shielded,
			boolean ultra) {
		if (eliminated) {
			return ultra ? "×" : "ВЫБЫЛА";
		}
		if (rescuing) {
			return ultra ? country.rescueSecondsRemaining() + "с" : "СП" + country.rescueSecondsRemaining();
		}
		if (coreDown) {
			return ultra ? "!" : "ЯДРО";
		}
		if (shielded) {
			return ultra ? "Щ" : "ЩИТ";
		}
		return ultra ? "!" : "УЯЗВ";
	}

	private static int statusColor(
			ArenaHudCountryState country,
			boolean eliminated,
			boolean rescuing,
			boolean coreDown,
			boolean shielded) {
		if (eliminated) {
			return COLOR_ELIMINATED;
		}
		if (rescuing) {
			return COLOR_RESCUE;
		}
		if (shielded) {
			return COLOR_SHIELD;
		}
		return COLOR_VULN;
	}

	private static void drawFlag(GuiGraphics graphics, Country country, int flagX, int flagY, int w, int h) {
		ResourceLocation texture = ArenaFighterFlagVisuals.flagTexture(country);
		boolean exists = ArenaFighterFlagVisuals.resourceExists(texture)
				&& !texture.equals(ArenaFighterFlagVisuals.FALLBACK_TEXTURE);

		if (LOGGED_FIRST_FLAG.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.info("ArenaRoundHudRenderer v3: first flag {} exists={}", texture, exists);
		}

		if (!exists) {
			graphics.fill(flagX, flagY, flagX + w, flagY + h, 0xFF666666);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				String code = country.getCode();
				int tx = flagX + (w - minecraft.font.width(code)) / 2;
				int ty = flagY + (h - 8) / 2;
				graphics.drawString(minecraft.font, code, tx, ty, 0xFFFFFFFF, false);
			}
			return;
		}

		graphics.blit(texture, flagX, flagY, w, h, 0.0F, 0.0F, FLAG_TEX_WIDTH, FLAG_TEX_HEIGHT, FLAG_TEX_WIDTH, FLAG_TEX_HEIGHT);
	}

	private static int stateColor(ArenaMatchState state) {
		return switch (state) {
			case WAITING_FOR_OPPONENT -> 0xFFFFFF55;
			case BATTLE -> 0xFFFF6666;
			case BREAK -> 0xFF55FFFF;
			case IDLE -> 0xFFFFD700;
		};
	}
}
