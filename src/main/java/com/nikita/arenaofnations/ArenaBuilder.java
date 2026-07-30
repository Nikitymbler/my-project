package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic large medieval coliseum builder (version 2).
 * Generates placements stage-by-stage without preloading the entire arena into memory.
 */
final class ArenaBuilder {
	static final int BLOCKS_PER_TICK = 500;

	enum Stage {
		CLEAR("Очистка"),
		FOUNDATION("Основание"),
		FLOOR("Пол поля"),
		SECTORS("Цветные сектора"),
		INNER_RING("Внутренний бортик"),
		STANDS("Трибуны"),
		OUTER_WALL("Внешняя стена"),
		PORTALS("Порталы стран"),
		TOWERS("Башни"),
		LIGHTING("Освещение и декор"),
		CORES("Ядра стран"),
		FINALIZE("Финал");

		final String label;

		Stage(String label) {
			this.label = label;
		}

		Stage next() {
			int i = ordinal() + 1;
			return i < values().length ? values()[i] : null;
		}
	}

	private final int cx;
	private final int cy;
	private final int cz;
	private final int minY;
	private final int maxY;

	private Stage stage = Stage.CLEAR;
	private int cursorX;
	private int cursorY;
	private int cursorZ;
	private boolean stageStarted;
	private boolean clearEntitiesDone;
	private long placed;
	private final long estimatedTotal;

	ArenaBuilder(BlockPos center, int minBuildY, int maxBuildY) {
		this.cx = center.getX();
		this.cy = center.getY();
		this.cz = center.getZ();
		this.minY = minBuildY;
		this.maxY = maxBuildY;
		this.estimatedTotal = estimateTotal();
		resetStageCursor();
	}

	Stage getStage() {
		return stage;
	}

	long getPlaced() {
		return placed;
	}

	long getEstimatedTotal() {
		return estimatedTotal;
	}

	int getProgressPercent() {
		if (estimatedTotal <= 0) {
			return 0;
		}
		return (int) Math.min(100L, (placed * 100L) / estimatedTotal);
	}

	boolean isComplete() {
		return stage == null;
	}

	/**
	 * @return number of block changes applied this call
	 */
	int process(ServerLevel level, int limit) {
		int used = 0;
		while (used < limit && stage != null) {
			int before = used;
			used += processOne(level, limit - used);
			if (used == before) {
				// stage advanced without placing, continue
				if (stage == null) {
					break;
				}
			}
		}
		return used;
	}

	private int processOne(ServerLevel level, int remaining) {
		if (remaining <= 0 || stage == null) {
			return 0;
		}
		if (!stageStarted) {
			resetStageCursor();
			stageStarted = true;
		}

		return switch (stage) {
			case CLEAR -> stepClear(level, remaining);
			case FOUNDATION -> stepFoundation(level, remaining);
			case FLOOR -> stepFloor(level, remaining);
			case SECTORS -> stepSectors(level, remaining);
			case INNER_RING -> stepInnerRing(level, remaining);
			case STANDS -> stepStands(level, remaining);
			case OUTER_WALL -> stepOuterWall(level, remaining);
			case PORTALS -> stepPortals(level, remaining);
			case TOWERS -> stepTowers(level, remaining);
			case LIGHTING -> stepLighting(level, remaining);
			case CORES -> stepCores(level, remaining);
			case FINALIZE -> {
				advanceStage();
				yield 0;
			}
		};
	}

	private void advanceStage() {
		stage = stage.next();
		stageStarted = false;
		if (stage != null) {
			resetStageCursor();
		}
	}

	private void resetStageCursor() {
		int r = ArenaPositions.CLEAR_RADIUS;
		cursorX = cx - r;
		cursorZ = cz - r;
		cursorY = cy + 1;
		if (stage == Stage.FOUNDATION) {
			cursorY = cy - ArenaPositions.FOUNDATION_DEPTH;
		} else if (stage == Stage.FLOOR || stage == Stage.SECTORS) {
			cursorY = cy;
		} else if (stage == Stage.INNER_RING) {
			cursorY = cy + 1;
		} else if (stage == Stage.STANDS) {
			cursorY = cy + 1;
		} else if (stage == Stage.OUTER_WALL) {
			cursorY = cy;
		} else if (stage == Stage.PORTALS) {
			cursorX = 0;
			cursorY = 0;
			cursorZ = 0;
		} else if (stage == Stage.TOWERS) {
			cursorX = 0;
			cursorY = 0;
			cursorZ = 0;
		} else if (stage == Stage.LIGHTING) {
			cursorX = 0;
			cursorY = 0;
			cursorZ = 0;
		} else if (stage == Stage.CORES) {
			cursorX = 0;
			cursorY = 0;
			cursorZ = 0;
		}
	}

	private int stepClear(ServerLevel level, int limit) {
		if (!clearEntitiesDone) {
			ArenaWorldCleanup.removeArenaEntities(level, new BlockPos(cx, cy, cz));
			clearEntitiesDone = true;
		}
		int used = 0;
		int r = ArenaPositions.CLEAR_RADIUS;
		int top = Math.min(maxY, cy + ArenaPositions.CLEAR_HEIGHT);
		int bottom = Math.max(minY, cy + 1);

		while (used < limit) {
			if (cursorY > top) {
				cursorY = bottom;
				cursorX++;
			}
			if (cursorX > cx + r) {
				cursorX = cx - r;
				cursorZ++;
			}
			if (cursorZ > cz + r) {
				advanceStage();
				return used;
			}

			if (ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), cursorX, cursorZ) <= (long) r * r) {
				BlockPos pos = new BlockPos(cursorX, cursorY, cursorZ);
				if (inWorld(pos) && !level.getBlockState(pos).isAir()) {
					set(level, pos, Blocks.AIR.defaultBlockState());
					used++;
					placed++;
				}
			}
			cursorY++;
		}
		return used;
	}

	private int stepFoundation(ServerLevel level, int limit) {
		int used = 0;
		int r = ArenaPositions.OUTER_RADIUS;
		int bottom = Math.max(minY, cy - ArenaPositions.FOUNDATION_DEPTH);

		while (used < limit) {
			if (cursorY > cy) {
				cursorY = bottom;
				cursorX++;
			}
			if (cursorX > cx + r) {
				cursorX = cx - r;
				cursorZ++;
			}
			if (cursorZ > cz + r) {
				advanceStage();
				return used;
			}

			double distSq = ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), cursorX, cursorZ);
			if (distSq <= (long) r * r) {
				BlockPos pos = new BlockPos(cursorX, cursorY, cursorZ);
				if (inWorld(pos)) {
					Block block = foundationBlock(cursorX, cursorY, cursorZ);
					set(level, pos, block.defaultBlockState());
					used++;
					placed++;
				}
			}
			cursorY++;
		}
		return used;
	}

	private int stepFloor(ServerLevel level, int limit) {
		int used = 0;
		int r = ArenaPositions.COMBAT_WALKABLE_RADIUS;

		while (used < limit) {
			if (cursorX > cx + r) {
				cursorX = cx - r;
				cursorZ++;
			}
			if (cursorZ > cz + r) {
				advanceStage();
				return used;
			}

			double distSq = ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), cursorX, cursorZ);
			if (distSq <= (long) r * r) {
				BlockPos pos = new BlockPos(cursorX, cy, cursorZ);
				if (inWorld(pos)) {
					set(level, pos, floorBlock(cursorX, cursorZ).defaultBlockState());
					used++;
					placed++;
				}
			}
			cursorX++;
		}
		return used;
	}

	private int stepSectors(ServerLevel level, int limit) {
		// Neutral pads at each of 20 spawn zones (country color applied on activation).
		int used = 0;
		int r = ArenaPositions.COMBAT_WALKABLE_RADIUS;

		while (used < limit) {
			if (cursorX > cx + r) {
				cursorX = cx - r;
				cursorZ++;
			}
			if (cursorZ > cz + r) {
				advanceStage();
				return used;
			}

			BlockPos center = new BlockPos(cx, cy, cz);
			if (ArenaPositions.distanceSqHorizontal(center, cursorX, cursorZ) <= (long) r * r) {
				for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
					BlockPos zone = ArenaCountryBaseLayout.spawnZoneCenter(center, slot).below();
					if (ArenaPositions.distanceSqHorizontal(zone, cursorX, cursorZ)
							<= (long) ArenaPositions.SECTOR_RADIUS * ArenaPositions.SECTOR_RADIUS) {
						BlockPos pos = new BlockPos(cursorX, cy, cursorZ);
						if (inWorld(pos)) {
							set(level, pos, ArenaCountryBaseLayout.neutralSectorBlock().defaultBlockState());
							used++;
							placed++;
						}
						break;
					}
				}
			}
			cursorX++;
		}
		return used;
	}

	private int stepInnerRing(ServerLevel level, int limit) {
		// Arena v4: no physical inner border. Keep center fully walkable.
		advanceStage();
		return 0;
	}

	private int stepStands(ServerLevel level, int limit) {
		int used = 0;
		int inner = ArenaCountryBaseLayout.STANDS_INNER_RADIUS;
		int outer = ArenaCountryBaseLayout.STANDS_OUTER_RADIUS;

		while (used < limit) {
			if (cursorY > cy + 6) {
				cursorY = cy + 1;
				cursorX++;
			}
			if (cursorX > cx + outer) {
				cursorX = cx - outer;
				cursorZ++;
			}
			if (cursorZ > cz + outer) {
				advanceStage();
				return used;
			}

			double dist = Math.sqrt(ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), cursorX, cursorZ));
			if (dist >= inner && dist <= outer) {
				double angleDeg = Math.toDegrees(Math.atan2(cursorX - cx, -(cursorZ - cz)));
				if (angleDeg < 0.0) {
					angleDeg += 360.0;
				}
				int slotIndex = (int) Math.round(angleDeg / ArenaCountryBaseLayout.BASE_ANGLE_STEP_DEGREES)
						% ArenaCountryBaseLayout.BASE_SLOT_COUNT;
				boolean stairGap = slotIndex % 5 == 0;
				int tier = (int) Math.floor(dist - inner);
				int standY = cy + 1 + Math.min(5, tier / 2);
				if (cursorY == standY) {
					BlockPos pos = new BlockPos(cursorX, cursorY, cursorZ);
					if (inWorld(pos) && !stairGap) {
						Block block = (tier % 2 == 0) ? Blocks.STONE_BRICK_STAIRS : Blocks.POLISHED_ANDESITE;
						BlockState state = block.defaultBlockState();
						if (block == Blocks.STONE_BRICK_STAIRS) {
							state = facingInwardStairs(cursorX, cursorZ);
						}
						set(level, pos, state);
						used++;
						placed++;
					}
				} else if (cursorY < standY && cursorY >= cy + 1) {
					BlockPos pos = new BlockPos(cursorX, cursorY, cursorZ);
					if (inWorld(pos)) {
						set(level, pos, Blocks.STONE_BRICKS.defaultBlockState());
						used++;
						placed++;
					}
				}
			}
			cursorY++;
		}
		return used;
	}

	private int stepOuterWall(ServerLevel level, int limit) {
		int used = 0;
		int rMin = ArenaCountryBaseLayout.OUTER_WALL_RADIUS - 1;
		int rMax = ArenaCountryBaseLayout.OUTER_WALL_RADIUS;
		int top = Math.min(maxY, cy + ArenaPositions.WALL_HEIGHT);

		while (used < limit) {
			if (cursorY > top) {
				cursorY = cy;
				cursorX++;
			}
			if (cursorX > cx + rMax) {
				cursorX = cx - rMax;
				cursorZ++;
			}
			if (cursorZ > cz + rMax) {
				advanceStage();
				return used;
			}

			double dist = Math.sqrt(ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), cursorX, cursorZ));
			if (dist >= rMin && dist <= rMax + 0.2) {
				BlockPos pos = new BlockPos(cursorX, cursorY, cursorZ);
				if (inWorld(pos)) {
					boolean gate = isCardinalGate(cursorX, cursorZ);
					BlockState state;
					if (gate && cursorY >= cy + 2 && cursorY <= cy + 6) {
						state = Blocks.IRON_BARS.defaultBlockState();
					} else if (gate && cursorY > cy + 6 && cursorY <= cy + 8) {
						state = Blocks.DEEPSLATE_TILES.defaultBlockState();
					} else {
						state = wallBlock(cursorX, cursorY, cursorZ).defaultBlockState();
					}
					set(level, pos, state);
					used++;
					placed++;

					// parapet
					if (cursorY == top && Math.floorMod(cursorX + cursorZ, 2) == 0) {
						BlockPos above = pos.above();
						if (inWorld(above) && above.getY() <= cy + ArenaPositions.MAX_DECOR_HEIGHT) {
							set(level, above, Blocks.STONE_BRICK_WALL.defaultBlockState());
							used++;
							placed++;
						}
					}
				}
			}
			cursorY++;
		}
		return used;
	}

	private int stepPortals(ServerLevel level, int limit) {
		// Compact slot markers only — full bases built in CORES stage.
		int used = 0;
		if (cursorX >= ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			advanceStage();
			return 0;
		}

		int slot = cursorX;
		BlockPos center = new BlockPos(cx, cy, cz);
		BlockPos marker = ArenaCountryBaseLayout.portalPosition(center, slot);
		if (inWorld(marker)) {
			set(level, marker, Blocks.STONE_BRICK_SLAB.defaultBlockState());
			used++;
			placed++;
		}
		cursorX++;
		if (cursorX >= ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			advanceStage();
		}
		return used;
	}

	private int buildPortal(ServerLevel level, BlockPos base, Country country, int limit) {
		int used = 0;
		int dx = Integer.signum(base.getX() - cx);
		int dz = Integer.signum(base.getZ() - cz);
		// Columns left/right relative to facing toward center
		int ox = dz;
		int oz = -dx;

		Block[] palette = portalPalette(country);

		for (int h = 0; h <= 5 && used < limit; h++) {
			BlockPos left = base.offset(ox * 2, h, oz * 2);
			BlockPos right = base.offset(-ox * 2, h, -oz * 2);
			BlockPos back = base.offset(dx, h, dz);
			if (inWorld(left)) {
				set(level, left, Blocks.STONE_BRICKS.defaultBlockState());
				used++;
				placed++;
			}
			if (used < limit && inWorld(right)) {
				set(level, right, Blocks.STONE_BRICKS.defaultBlockState());
				used++;
				placed++;
			}
			if (used < limit && inWorld(back)) {
				set(level, back, Blocks.DEEPSLATE_TILES.defaultBlockState());
				used++;
				placed++;
			}
		}

		// Arch top
		for (int i = -2; i <= 2 && used < limit; i++) {
			BlockPos arch = base.offset(ox * i, 6, oz * i);
			if (inWorld(arch)) {
				set(level, arch, Blocks.STONE_BRICKS.defaultBlockState());
				used++;
				placed++;
			}
		}

		// Color marker and lanterns
		BlockPos marker = base.above(3);
		if (used < limit && inWorld(marker)) {
			set(level, marker, palette[0].defaultBlockState());
			used++;
			placed++;
		}
		BlockPos lantern = base.offset(0, 5, 0);
		if (used < limit && inWorld(lantern)) {
			set(level, lantern, Blocks.LANTERN.defaultBlockState());
			used++;
			placed++;
		}
		BlockPos chain = lantern.above();
		if (used < limit && inWorld(chain) && chain.getY() <= cy + ArenaPositions.MAX_DECOR_HEIGHT) {
			set(level, chain, Blocks.CHAIN.defaultBlockState());
			used++;
			placed++;
		}
		return used;
	}

	private int stepTowers(ServerLevel level, int limit) {
		// Legacy four corner towers removed — 20 compact country bases replace them.
		advanceStage();
		return 0;
	}

	private int stepLighting(ServerLevel level, int limit) {
		int used = 0;
		// Place lanterns around inner ring at fixed angular steps.
		int count = 24;
		if (cursorX >= count) {
			advanceStage();
			return 0;
		}

		while (used < limit && cursorX < count) {
			double angle = cursorX * (Math.PI * 2.0 / count);
			int x = cx + (int) Math.round(Math.cos(angle) * (ArenaPositions.CENTER_PATTERN_RADIUS + 1));
			int z = cz + (int) Math.round(Math.sin(angle) * (ArenaPositions.CENTER_PATTERN_RADIUS + 1));
			BlockPos pos = new BlockPos(x, cy + 3, z);
			if (inWorld(pos)) {
				set(level, pos, Blocks.LANTERN.defaultBlockState());
				used++;
				placed++;
			}
			BlockPos support = pos.above();
			if (inWorld(support) && support.getY() <= cy + ArenaPositions.MAX_DECOR_HEIGHT) {
				set(level, support, Blocks.CHAIN.defaultBlockState());
				used++;
				placed++;
			}
			cursorX++;
		}

		if (cursorX >= count) {
			advanceStage();
		}
		return used;
	}

	private int stepCores(ServerLevel level, int limit) {
		if (cursorX >= ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			advanceStage();
			return 0;
		}

		BlockPos center = new BlockPos(cx, cy, cz);
		int slot = cursorX;
		int placedBlocks = ArenaCoreBuilder.buildPhysicalBaseAtSlot(level, center, slot, Country.ALL.get(slot));
		placed += placedBlocks;
		cursorX++;
		if (cursorX >= ArenaCountryBaseLayout.BASE_SLOT_COUNT) {
			advanceStage();
		}
		return Math.min(limit, Math.max(1, placedBlocks));
	}

	private Block foundationBlock(int x, int y, int z) {
		int h = cy - y;
		int pattern = Math.floorMod(x * 3 + z * 7 + y, 11);
		if (h == 0) {
			return Blocks.STONE_BRICKS;
		}
		if (pattern == 0) {
			return Blocks.COBBLESTONE;
		}
		if (pattern == 1) {
			return Blocks.POLISHED_ANDESITE;
		}
		return Blocks.STONE;
	}

	private Block floorBlock(int x, int z) {
		double dist = Math.sqrt(ArenaPositions.distanceSqHorizontal(new BlockPos(cx, cy, cz), x, z));
		// Center emblem
		if (dist <= 4.5) {
			if (dist <= 1.5) {
				return Blocks.CHISELED_STONE_BRICKS;
			}
			return Math.floorMod(x + z, 2) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_ANDESITE;
		}
		// Concentric rings
		if (Math.abs(dist % 7.0 - 3.5) < 0.55) {
			return Blocks.DEEPSLATE_TILES;
		}
		if (Math.abs(dist % 14.0 - 7.0) < 0.55) {
			return Blocks.STONE_BRICKS;
		}
		// 20 radial lanes toward bases (walkable lanes, width >= 5 blocks)
		double deg = Math.toDegrees(Math.atan2(x - cx, -(z - cz)));
		if (deg < 0.0) {
			deg += 360.0;
		}
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			double slotDeg = ArenaCountryBaseLayout.slotAngleDegrees(slot);
			double diff = Math.abs(((deg - slotDeg + 180.0) % 360.0) - 180.0);
			double lateral = Math.sin(Math.toRadians(diff)) * dist;
			if (Math.abs(lateral) <= 2.5
					&& dist > 6.0
					&& dist < ArenaPositions.COMBAT_WALKABLE_RADIUS - 1.0) {
				return Blocks.STONE_BRICKS;
			}
		}
		int ring = (int) Math.floor(dist / 5.0);
		if (ring % 2 == 0) {
			return Blocks.SMOOTH_STONE;
		}
		return Math.floorMod(x * 5 + z * 3, 13) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.SMOOTH_STONE;
	}

	private Block[] portalPalette(Country country) {
		Block primary = ArenaCountryBaseLayout.primaryBlock(country);
		Block accent = ArenaCountryBaseLayout.sectorBlock(country, 0, 0);
		return new Block[] {primary, accent};
	}

	private Block wallBlock(int x, int y, int z) {
		int along = Math.floorMod(x + z * 3, 21);
		if (along % 7 == 0) {
			return Blocks.CHISELED_STONE_BRICKS;
		}
		if (along % 11 == 0) {
			return Blocks.CRACKED_STONE_BRICKS;
		}
		if (along == 3 || along == 15) {
			return Blocks.MOSSY_STONE_BRICKS;
		}
		if (y == cy + ArenaPositions.WALL_HEIGHT) {
			return Blocks.DEEPSLATE_TILES;
		}
		return Blocks.STONE_BRICKS;
	}

	private boolean isCardinalGate(int x, int z) {
		return false;
	}

	private BlockState facingInwardStairs(int x, int z) {
		double angle = Math.atan2(z - cz, x - cx);
		net.minecraft.core.Direction dir;
		if (angle >= -Math.PI / 4 && angle < Math.PI / 4) {
			dir = net.minecraft.core.Direction.WEST; // face toward center from east
		} else if (angle >= Math.PI / 4 && angle < 3 * Math.PI / 4) {
			dir = net.minecraft.core.Direction.NORTH;
		} else if (angle >= -3 * Math.PI / 4 && angle < -Math.PI / 4) {
			dir = net.minecraft.core.Direction.SOUTH;
		} else {
			dir = net.minecraft.core.Direction.EAST;
		}
		return Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dir);
	}

	private boolean inWorld(BlockPos pos) {
		return pos.getY() >= minY && pos.getY() <= maxY;
	}

	private void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
	}

	private static long estimateTotal() {
		long clear = (long) Math.PI * ArenaPositions.CLEAR_RADIUS * ArenaPositions.CLEAR_RADIUS * ArenaPositions.CLEAR_HEIGHT;
		long foundation = (long) Math.PI * ArenaPositions.OUTER_RADIUS * ArenaPositions.OUTER_RADIUS * (ArenaPositions.FOUNDATION_DEPTH + 1);
		long floor = (long) Math.PI * ArenaPositions.COMBAT_WALKABLE_RADIUS * ArenaPositions.COMBAT_WALKABLE_RADIUS;
		int standsInner = ArenaCountryBaseLayout.STANDS_INNER_RADIUS;
		int standsOuter = ArenaCountryBaseLayout.STANDS_OUTER_RADIUS;
		long stands = (long) Math.PI * (standsOuter * standsOuter - standsInner * standsInner) * 6;
		int wallR = ArenaCountryBaseLayout.OUTER_WALL_RADIUS;
		long wall = (long) (2 * Math.PI * wallR) * ArenaPositions.WALL_HEIGHT * 2;
		long bases = (long) ArenaCountryBaseLayout.BASE_SLOT_COUNT * 220L;
		return clear + foundation + floor + stands + wall + bases + 5000;
	}
}
