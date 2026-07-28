package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Removes arena-tagged entities and clears block regions on rebuild/reset.
 */
public final class ArenaWorldCleanup {
	public static final String BASE_CODE_TAG = "arena_of_nations.base_code";
	public static final String CORE_DISPLAY_TAG = ArenaCoreDisplayManager.DISPLAY_TAG;

	private ArenaWorldCleanup() {
	}

	public static int removeArenaEntities(ServerLevel level, BlockPos center) {
		int radius = ArenaCountryBaseLayout.CLEAR_RADIUS + 4;
		AABB box = new AABB(
				center.getX() - radius,
				level.getMinBuildHeight(),
				center.getZ() - radius,
				center.getX() + radius + 1,
				level.getMaxBuildHeight(),
				center.getZ() + radius + 1);
		int removed = 0;
		for (Entity entity : level.getEntitiesOfClass(Entity.class, box, ArenaWorldCleanup::isArenaManaged)) {
			entity.discard();
			removed++;
		}
		ArenaBaseCodeDisplay.clearTracking();
		return removed;
	}

	private static boolean isArenaManaged(Entity entity) {
		if (entity.getTags().contains(BASE_CODE_TAG)) {
			return true;
		}
		if (entity.getTags().contains(CORE_DISPLAY_TAG)) {
			return true;
		}
		return entity.getTags().contains("arena_of_nations.arena_entity");
	}
}
