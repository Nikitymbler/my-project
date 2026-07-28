package com.nikita.arenaofnations.client;

import com.nikita.arenaofnations.ArenaOfNations;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Shared wide (Steve) humanoid layer used by all arena fighters.
 */
public final class ArenaFighterModels {
	public static final ModelLayerLocation HUMANOID_LAYER = new ModelLayerLocation(
			ArenaOfNations.id("arena_fighter_humanoid"),
			"main");

	private ArenaFighterModels() {
	}

	public static LayerDefinition createHumanoidLayer() {
		// false = wide/Steve arms (not slim/Alex). 64x64 player skin UVs.
		return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
	}
}
