package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Registration for arena custom entities.
 */
public final class ArenaEntities {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	public static final EntityType<ArenaFighterEntity> ARENA_FIGHTER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			ArenaOfNations.id("arena_fighter"),
			EntityType.Builder.of(ArenaFighterEntity::new, MobCategory.CREATURE)
					.sized(0.6F, 1.8F)
					.clientTrackingRange(10)
					.updateInterval(3)
					.build(ArenaOfNations.id("arena_fighter").toString()));

	private ArenaEntities() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		FabricDefaultAttributeRegistry.register(ARENA_FIGHTER, ArenaFighterEntity.createAttributes());
		ArenaOfNations.LOGGER.info("Registered entity type {}", ArenaOfNations.id("arena_fighter"));
	}
}
