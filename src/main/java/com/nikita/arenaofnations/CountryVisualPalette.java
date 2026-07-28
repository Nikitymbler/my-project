package com.nikita.arenaofnations;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Per-country accent palette for base trim and HUD (not full-block painting).
 */
public final class CountryVisualPalette {
	public record Palette(Block primary, Block secondary, int hudAccentArgb) {
	}

	private static final Palette[] PALETTES = {
			palette(Blocks.RED_CONCRETE, Blocks.RED_WOOL, 0xFFE03030),           // RU
			palette(Blocks.BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, 0xFF3070E0), // UA
			palette(Blocks.GREEN_CONCRETE, Blocks.RED_WOOL, 0xFF30A030),       // BY
			palette(Blocks.CYAN_CONCRETE, Blocks.YELLOW_CONCRETE, 0xFF00A8C8), // KZ
			palette(Blocks.YELLOW_CONCRETE, Blocks.GREEN_CONCRETE, 0xFFF0C020),// LT
			palette(Blocks.WHITE_CONCRETE, Blocks.RED_CONCRETE, 0xFFF0F0F0),   // PL
			palette(Blocks.LIGHT_BLUE_CONCRETE, Blocks.WHITE_CONCRETE, 0xFF4080D0), // IL
			palette(Blocks.ORANGE_CONCRETE, Blocks.BLUE_CONCRETE, 0xFFE06020), // AM
			palette(Blocks.LIME_CONCRETE, Blocks.WHITE_CONCRETE, 0xFF30B050),  // UZ
			palette(Blocks.RED_CONCRETE, Blocks.GREEN_CONCRETE, 0xFFD03030),     // TJ
			palette(Blocks.WHITE_CONCRETE, Blocks.RED_CONCRETE, 0xFFF8F8F8),   // GE
			palette(Blocks.RED_CONCRETE, Blocks.YELLOW_CONCRETE, 0xFFE02030),  // KG
			palette(Blocks.GREEN_CONCRETE, Blocks.WHITE_CONCRETE, 0xFF208040), // TM
			palette(Blocks.BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, 0xFF2060C0), // MD
			palette(Blocks.LIGHT_BLUE_CONCRETE, Blocks.RED_CONCRETE, 0xFF30A0D0),// AZ
			palette(Blocks.RED_TERRACOTTA, Blocks.WHITE_CONCRETE, 0xFF903030), // LV
			palette(Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE, 0xFFD02020),   // AL
			palette(Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE, 0xFF30A050),   // BG
			palette(Blocks.RED_CONCRETE, Blocks.YELLOW_CONCRETE, 0xFFE03030),  // CN
			palette(Blocks.BLUE_CONCRETE, Blocks.RED_CONCRETE, 0xFF3050A0),  // US
	};

	private CountryVisualPalette() {
	}

	private static Palette palette(Block primary, Block secondary, int hudAccent) {
		return new Palette(primary, secondary, hudAccent);
	}

	public static Palette of(Country country) {
		if (country == null) {
			return palette(Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, 0xFFAAAAAA);
		}
		return PALETTES[Math.floorMod(country.ordinal(), PALETTES.length)];
	}

	public static Block primaryBlock(Country country) {
		return of(country).primary();
	}

	public static Block secondaryBlock(Country country) {
		return of(country).secondary();
	}

	public static int hudAccent(Country country) {
		return of(country).hudAccentArgb();
	}

	public static Block neutralTrim() {
		return Blocks.LIGHT_GRAY_CONCRETE;
	}

	public static Block neutralSecondary() {
		return Blocks.STONE_BRICKS;
	}
}
