package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-only held-item look-up by fighter tier.
 * Stacks are never placed into entity equipment slots.
 */
public final class ArenaFighterEquipmentVisuals {
	public record Hands(ItemStack mainHand, ItemStack offHand) {
	}

	private static final Hands SCOUT = hands(Items.TRIDENT.getDefaultInstance(), ItemStack.EMPTY);
	private static final Hands WARRIOR = hands(Items.IRON_SWORD.getDefaultInstance(), Items.SHIELD.getDefaultInstance());
	private static final Hands ELITE = hands(Items.IRON_AXE.getDefaultInstance(), Items.SHIELD.getDefaultInstance());
	private static final Hands CHAMPION = hands(Items.DIAMOND_SWORD.getDefaultInstance(), Items.SHIELD.getDefaultInstance());
	private static final Hands TITAN = hands(Items.NETHERITE_AXE.getDefaultInstance(), ItemStack.EMPTY);

	private ArenaFighterEquipmentVisuals() {
	}

	public static Hands handsFor(ArenaFighterEntity entity) {
		return handsFor(entity.getArenaTier());
	}

	public static Hands handsFor(FighterTier tier) {
		FighterTier resolved = tier != null ? tier : FighterTier.SCOUT;
		return switch (resolved) {
			case SCOUT -> SCOUT;
			case WARRIOR -> WARRIOR;
			case HEAVY -> ELITE;
			case HERO -> CHAMPION;
			case TITAN -> TITAN;
		};
	}

	private static Hands hands(ItemStack mainHand, ItemStack offHand) {
		return new Hands(mainHand, offHand);
	}
}
