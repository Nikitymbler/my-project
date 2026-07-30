package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Registration for custom visual-only arena items.
 */
public final class ArenaItems {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	public static final Item MEDIEVAL_SPEAR = Registry.register(
			BuiltInRegistries.ITEM,
			ArenaOfNations.id("medieval_spear"),
			new Item(new Item.Properties().stacksTo(1)));

	private ArenaItems() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ArenaOfNations.LOGGER.info("Registered item {}", ArenaOfNations.id("medieval_spear"));
	}
}
