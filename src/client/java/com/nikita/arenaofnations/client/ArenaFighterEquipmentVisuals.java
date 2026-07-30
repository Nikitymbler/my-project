package com.nikita.arenaofnations.client;

import java.util.concurrent.atomic.AtomicBoolean;

import com.nikita.arenaofnations.ArenaFighterEntity;
import com.nikita.arenaofnations.ArenaItems;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.FighterTier;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client-only held-item look-up by fighter tier.
 * Stacks are never placed into entity equipment slots.
 */
public final class ArenaFighterEquipmentVisuals {
	public record Hands(ItemStack mainHand, ItemStack offHand) {
	}

	public static final String WEAPON_MODE = "ITEM_STACK";
	public static final String WEAPON_VISUAL_TYPE = "MEDIEVAL_GLAIVE";
	public static final ResourceLocation SPEAR_MODEL_RESOURCE = ArenaOfNations.id("models/item/medieval_spear.json");
	public static final ResourceLocation SPEAR_TEXTURE_RESOURCE = ArenaOfNations.id("textures/item/medieval_spear.png");
	public static final int ACTIVE_WEAPON_RENDER_PATHS = 1;
	public static final int TRIDENT_RENDER_PATHS = 0;
	public static final boolean WEAPON_ATTACHED_TO_RIGHT_ARM = true;
	/** Combined approximate third-person scale (model display 0.92 × layer 1.05). */
	public static final float WEAPON_SCALE = 0.966F;
	public static final float WEAPON_ANGLE_DEGREES = 70.0F;
	private static final AtomicBoolean MISSING_WEAPON_RESOURCE_LOGGED = new AtomicBoolean(false);

	private static final Hands SCOUT = hands(ArenaItems.MEDIEVAL_SPEAR.getDefaultInstance(), ItemStack.EMPTY);
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

	public static boolean spearModelExists() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.getResourceManager().getResource(SPEAR_MODEL_RESOURCE).isPresent();
	}

	public static boolean spearTextureExists() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.getResourceManager().getResource(SPEAR_TEXTURE_RESOURCE).isPresent();
	}

	public static ResourceLocation mainHandItemId() {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(SCOUT.mainHand().getItem());
	}

	public static void ensureWeaponDiagnosticsLogged() {
		boolean model = spearModelExists();
		boolean texture = spearTextureExists();
		if ((!model || !texture) && MISSING_WEAPON_RESOURCE_LOGGED.compareAndSet(false, true)) {
			ArenaOfNations.LOGGER.error(
					"Missing medieval glaive resources: modelExists={}, textureExists={}, model={}, texture={}",
					model,
					texture,
					SPEAR_MODEL_RESOURCE,
					SPEAR_TEXTURE_RESOURCE);
		}
		if (model && texture) {
			MISSING_WEAPON_RESOURCE_LOGGED.set(false);
		}
	}
}
