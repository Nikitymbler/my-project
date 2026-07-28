package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synchronous arena region wipe for /arena_setup_clear and rebuild prep.
 */
public final class ArenaRegionClear {
	private ArenaRegionClear() {
	}

	public static int clearBlocks(ServerLevel level, BlockPos center) {
		int radius = ArenaCountryBaseLayout.CLEAR_RADIUS;
		int top = Math.min(level.getMaxBuildHeight() - 1, center.getY() + ArenaPositions.CLEAR_HEIGHT);
		int bottom = Math.max(level.getMinBuildHeight(), center.getY() + 1);
		int cleared = 0;

		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
				if (ArenaPositions.distanceSqHorizontal(center, x, z) > (long) radius * radius) {
					continue;
				}
				for (int y = bottom; y <= top; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
						cleared++;
					}
				}
			}
		}
		return cleared;
	}

	public static int clearArena(ServerLevel level, BlockPos center) {
		int blocks = clearBlocks(level, center);
		int entities = ArenaWorldCleanup.removeArenaEntities(level, center);
		ArenaBaseCodeDisplay.clearTracking();
		return blocks + entities;
	}
}
