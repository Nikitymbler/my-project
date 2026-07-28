package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Gate fortress country bases v4 — twin 3×3 towers, central gate, visible core, flat spawn platform.
 */
public final class ArenaCoreBuilder {
	private ArenaCoreBuilder() {
	}

	public static void applyVisual(ServerLevel level, BlockPos arenaCenter, Country country, ArenaCoreState.CoreVisual visual) {
		int slot = ArenaMatchManager.get().getBaseSlot(country);
		if (slot < 0) {
			ArenaOfNations.LOGGER.warn("applyVisual without base slot for {}", country.getId());
			return;
		}
		applyVisualAtSlot(level, arenaCenter, slot, country, visual);
		if (visual != ArenaCoreState.CoreVisual.INACTIVE) {
			ArenaCoreDisplayManager.get().updateForCountry(level, arenaCenter, country);
		} else {
			ArenaCoreDisplayManager.get().hideSlot(level, slot);
		}
	}

	public static void applyVisualAtSlot(
			ServerLevel level,
			BlockPos arenaCenter,
			int slot,
			Country country,
			ArenaCoreState.CoreVisual visual) {
		buildSpawnPlatform(level, arenaCenter, slot, country, visual);
		buildFortressBase(level, arenaCenter, slot, country, visual);
	}

	public static int buildPhysicalBaseAtSlot(
			ServerLevel level,
			BlockPos arenaCenter,
			int slot,
			Country country) {
		applyVisualAtSlot(level, arenaCenter, slot, country, ArenaCoreState.CoreVisual.INACTIVE);
		return 360;
	}

	public static int buildAllPhysicalBases(ServerLevel level, BlockPos arenaCenter) {
		int count = 0;
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			buildPhysicalBaseAtSlot(level, arenaCenter, slot, Country.ALL.get(slot));
			count += 360;
		}
		return count;
	}

	public static void resetAllPhysicalBases(ServerLevel level, BlockPos arenaCenter) {
		ArenaCoreDisplayManager.get().clearAll(level, arenaCenter);
		ArenaBaseCodeDisplay.clearAll(level, arenaCenter);
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			applyVisualAtSlot(level, arenaCenter, slot, Country.ALL.get(slot), ArenaCoreState.CoreVisual.INACTIVE);
		}
	}

	private static void buildSpawnPlatform(
			ServerLevel level,
			BlockPos arenaCenter,
			int slot,
			Country country,
			ArenaCoreState.CoreVisual visual) {
		BlockPos zoneCenter = ArenaCountryBaseLayout.spawnZoneCenter(arenaCenter, slot);
		Direction inward = ArenaCountryBaseLayout.inwardDirection(slot);
		Direction side = ArenaCountryBaseLayout.outwardDirection(slot).getClockWise();
		int halfW = ArenaCountryBaseLayout.SPAWN_PLATFORM_WIDTH / 2;
		int depth = ArenaCountryBaseLayout.SPAWN_PLATFORM_DEPTH;

		Block floor = Blocks.SMOOTH_STONE;
		Block trim = visual == ArenaCoreState.CoreVisual.INACTIVE
				? Blocks.STONE_BRICK_SLAB
				: CountryVisualPalette.primaryBlock(country);

		for (int forward = 0; forward < depth; forward++) {
			for (int lateral = -halfW; lateral <= halfW; lateral++) {
				BlockPos floorPos = zoneCenter.relative(inward, forward).relative(side, lateral).below();
				boolean edge = forward == 0 || forward == depth - 1 || lateral == -halfW || lateral == halfW;
				if (edge && (forward == 0 || lateral == -halfW || lateral == halfW)) {
					set(level, floorPos, trim.defaultBlockState());
				} else {
					set(level, floorPos, floor.defaultBlockState());
				}
				BlockPos feet = floorPos.above();
				set(level, feet, Blocks.AIR.defaultBlockState());
				set(level, feet.above(), Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static void buildFortressBase(
			ServerLevel level,
			BlockPos arenaCenter,
			int slot,
			Country country,
			ArenaCoreState.CoreVisual visual) {
		BlockPos core = ArenaCountryBaseLayout.corePosition(arenaCenter, slot);
		Direction outward = ArenaCountryBaseLayout.outwardDirection(slot);
		Direction inward = outward.getOpposite();
		Direction side = outward.getClockWise();

		VisualMaterials mats = VisualMaterials.forState(country, visual);
		BlockPos footing = core.below();

		clearFortressVolume(level, footing, outward, inward, side);

		// Pedestal (1 block) — 7×5 footprint
		for (int o = -1; o <= 1; o++) {
			for (int s = -3; s <= 3; s++) {
				BlockPos pos = footing.relative(outward, o).relative(side, s);
				boolean center = o == 0 && s == 0;
				set(level, pos, (center ? Blocks.CHISELED_STONE_BRICKS : mats.stone).defaultBlockState());
			}
		}

		// Left tower 3×3, height 9
		for (int h = 1; h <= 9; h++) {
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					BlockPos tower = footing.relative(side, 4 + dx).relative(outward, dz).above(h);
					Block block = pickTowerBlock(mats, h, dx, dz);
					set(level, tower, block.defaultBlockState());
				}
			}
		}

		// Right tower 3×3 (mirror)
		for (int h = 1; h <= 9; h++) {
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					BlockPos tower = footing.relative(side, -6 + dx).relative(outward, dz).above(h);
					Block block = pickTowerBlock(mats, h, dx, dz);
					set(level, tower, block.defaultBlockState());
				}
			}
		}

		// Country accent stripes (≤15% of face)
		if (visual != ArenaCoreState.CoreVisual.INACTIVE) {
			for (int h = 3; h <= 5; h++) {
				set(level, footing.relative(side, 5).relative(outward, 1).above(h), mats.trim.defaultBlockState());
				set(level, footing.relative(side, -5).relative(outward, 1).above(h), mats.trim.defaultBlockState());
			}
		}

		// Central gate arch — 3 wide, 4 tall passage
		for (int h = 1; h <= 4; h++) {
			for (int s = -1; s <= 1; s++) {
				BlockPos gate = footing.relative(side, s).relative(outward, 1).above(h);
				set(level, gate, Blocks.AIR.defaultBlockState());
			}
		}
		for (int s = -2; s <= 2; s++) {
			if (Math.abs(s) <= 1) {
				continue;
			}
			BlockPos bar = footing.relative(side, s).relative(outward, 1).above(5);
			set(level, bar, Blocks.IRON_BARS.defaultBlockState());
		}
		// Arch lintel
		for (int s = -1; s <= 1; s++) {
			BlockPos lintel = footing.relative(side, s).relative(outward, 1).above(5);
			set(level, lintel, mats.trim.defaultBlockState());
		}
		// Stair arch decor (sides only, not in walkway)
		BlockPos stairL = footing.relative(side, 2).relative(outward, 1).above(4);
		BlockPos stairR = footing.relative(side, -2).relative(outward, 1).above(4);
		set(level, stairL, stairState(Blocks.STONE_BRICK_STAIRS, inward, side, Half.BOTTOM, StairsShape.STRAIGHT));
		set(level, stairR, stairState(Blocks.STONE_BRICK_STAIRS, inward, side.getOpposite(), Half.BOTTOM, StairsShape.STRAIGHT));

		// Core pedestal + visible core
		BlockPos coreBase = footing.above();
		set(level, coreBase, Blocks.DEEPSLATE_TILES.defaultBlockState());
		set(level, coreBase.relative(side), mats.trim.defaultBlockState());
		set(level, coreBase.relative(side.getOpposite()), mats.trim.defaultBlockState());
		set(level, core, mats.coreBlock.defaultBlockState());
		set(level, core.relative(inward), Blocks.AIR.defaultBlockState());
		set(level, core.relative(inward).above(), Blocks.AIR.defaultBlockState());

		// Back wall
		for (int s = -3; s <= 3; s++) {
			for (int h = 1; h <= 6; h++) {
				set(level, footing.relative(side, s).relative(outward, 2).above(h), mats.stone.defaultBlockState());
			}
		}

		// Battlements
		for (int s = -6; s <= 6; s += 3) {
			if (Math.abs(s) <= 1) {
				continue;
			}
			BlockPos batt = footing.relative(side, s).relative(outward, 1).above(10);
			set(level, batt, Blocks.STONE_BRICK_WALL.defaultBlockState());
		}

		// Permanent lantern posts (stone brick + chain) — independent of core/trim/status blocks.
		BlockPos leftPost = footing.relative(side, 5).above(9);
		BlockPos rightPost = footing.relative(side, -5).above(9);
		set(level, leftPost, Blocks.STONE_BRICKS.defaultBlockState());
		set(level, rightPost, Blocks.STONE_BRICKS.defaultBlockState());
		set(level, leftPost.above(), Blocks.CHAIN.defaultBlockState());
		set(level, rightPost.above(), Blocks.CHAIN.defaultBlockState());
		if (mats.lit) {
			set(level, leftPost.above(2), Blocks.LANTERN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
			set(level, rightPost.above(2), Blocks.LANTERN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
			BlockPos gateChain = footing.relative(outward, 1).above(9);
			set(level, gateChain, Blocks.STONE_BRICKS.defaultBlockState());
			set(level, gateChain.above(), Blocks.CHAIN.defaultBlockState());
			set(level, gateChain.above(2), Blocks.SOUL_LANTERN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
		} else {
			set(level, leftPost.above(2), Blocks.AIR.defaultBlockState());
			set(level, rightPost.above(2), Blocks.AIR.defaultBlockState());
			BlockPos gateChain = footing.relative(outward, 1).above(9);
			set(level, gateChain, Blocks.STONE_BRICKS.defaultBlockState());
			set(level, gateChain.above(), Blocks.CHAIN.defaultBlockState());
			set(level, gateChain.above(2), Blocks.AIR.defaultBlockState());
		}

		// Flat approach inward — no blocking stairs in walkway
		for (int i = 1; i <= 7; i++) {
			for (int h = 1; h <= 6; h++) {
				BlockPos clear = footing.relative(inward, i).above(h);
				if (!clear.equals(core) && !clear.equals(core.above())) {
					set(level, clear, Blocks.AIR.defaultBlockState());
				}
			}
		}
		// Side decor chains (off walkway)
		set(level, footing.relative(side, 3).relative(outward, 2).above(6), Blocks.CHAIN.defaultBlockState());
		set(level, footing.relative(side, -3).relative(outward, 2).above(6), Blocks.CHAIN.defaultBlockState());
	}

	private static Block pickTowerBlock(VisualMaterials mats, int height, int dx, int dz) {
		if (height >= 8) {
			return Blocks.STONE_BRICK_WALL;
		}
		if (height >= 7) {
			return Blocks.CHISELED_STONE_BRICKS;
		}
		if ((dx + dz) % 2 == 0 && height % 3 == 0) {
			return Blocks.CRACKED_STONE_BRICKS;
		}
		return mats.stone;
	}

	private static BlockState stairState(
			Block block,
			Direction facing,
			Direction axis,
			Half half,
			StairsShape shape) {
		return block.defaultBlockState()
				.setValue(StairBlock.FACING, facing)
				.setValue(StairBlock.HALF, half)
				.setValue(StairBlock.SHAPE, shape);
	}

	private static void clearFortressVolume(
			ServerLevel level,
			BlockPos footing,
			Direction outward,
			Direction inward,
			Direction side) {
		for (int o = -2; o <= 3; o++) {
			for (int s = -7; s <= 7; s++) {
				for (int h = 0; h <= 12; h++) {
					BlockPos pos = footing.relative(outward, o).relative(side, s).above(h);
					set(level, pos, Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	public static int buildAllInactive(ServerLevel level, BlockPos arenaCenter) {
		return buildAllPhysicalBases(level, arenaCenter);
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
			return;
		}
		// Suppress item drops when clearing/replacing lanterns and other blocks during arena updates.
		level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
	}

	private record VisualMaterials(Block stone, Block trim, Block coreBlock, boolean lit) {
		static VisualMaterials forState(Country country, ArenaCoreState.CoreVisual visual) {
			Block trim = visual == ArenaCoreState.CoreVisual.INACTIVE
					? CountryVisualPalette.neutralTrim()
					: CountryVisualPalette.primaryBlock(country);
			return switch (visual) {
				case ACTIVE -> new VisualMaterials(Blocks.STONE_BRICKS, trim, Blocks.LODESTONE, true);
				case DAMAGED -> new VisualMaterials(Blocks.CRACKED_STONE_BRICKS, CountryVisualPalette.secondaryBlock(country),
						Blocks.LODESTONE, true);
				case DESTROYED -> new VisualMaterials(Blocks.DEEPSLATE_TILES, Blocks.GRAY_CONCRETE,
						Blocks.CRYING_OBSIDIAN, false);
				case INACTIVE -> new VisualMaterials(Blocks.POLISHED_ANDESITE, CountryVisualPalette.neutralTrim(),
						Blocks.LODESTONE, false);
			};
		}
	}
}
