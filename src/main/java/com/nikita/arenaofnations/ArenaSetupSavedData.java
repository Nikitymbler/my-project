package com.nikita.arenaofnations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent arena setup for the current world (overworld DimensionDataStorage).
 * File: {@code data/arena_of_nations_setup.dat}
 */
public final class ArenaSetupSavedData extends SavedData {
	public static final String DATA_NAME = "arena_of_nations_setup";
	public static final int CURRENT_BUILD_VERSION = 1;

	public static final Factory<ArenaSetupSavedData> FACTORY = new Factory<>(
			ArenaSetupSavedData::new,
			ArenaSetupSavedData::load,
			null);

	private boolean configured;
	private boolean built;
	private int buildVersion;
	private String dimension = "";
	private int centerX;
	private int centerY;
	private int centerZ;

	public ArenaSetupSavedData() {
	}

	public static ArenaSetupSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		ArenaSetupSavedData data = new ArenaSetupSavedData();
		data.configured = tag.contains("configured") && tag.getBoolean("configured");
		data.built = tag.contains("built") && tag.getBoolean("built");
		data.buildVersion = tag.contains("build_version") ? tag.getInt("build_version") : 0;
		data.dimension = tag.contains("dimension") ? tag.getString("dimension") : "";
		data.centerX = tag.contains("center_x") ? tag.getInt("center_x") : 0;
		data.centerY = tag.contains("center_y") ? tag.getInt("center_y") : 0;
		data.centerZ = tag.contains("center_z") ? tag.getInt("center_z") : 0;
		return data;
	}

	public static ArenaSetupSavedData get(MinecraftServer server) {
		if (server == null) {
			ArenaOfNations.LOGGER.error("Cannot access arena setup: MinecraftServer is null");
			return null;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			ArenaOfNations.LOGGER.error("Cannot access arena setup: overworld is unavailable");
			return null;
		}
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("configured", configured);
		tag.putBoolean("built", built);
		tag.putInt("build_version", buildVersion);
		tag.putString("dimension", dimension == null ? "" : dimension);
		tag.putInt("center_x", centerX);
		tag.putInt("center_y", centerY);
		tag.putInt("center_z", centerZ);
		return tag;
	}

	public void configure(ResourceLocation dimensionId, BlockPos floorCenter) {
		this.configured = true;
		this.built = false;
		this.buildVersion = CURRENT_BUILD_VERSION;
		this.dimension = dimensionId.toString();
		this.centerX = floorCenter.getX();
		this.centerY = floorCenter.getY();
		this.centerZ = floorCenter.getZ();
		setDirty();
	}

	public void markBuilt() {
		this.built = true;
		setDirty();
	}

	public void clearSetup() {
		this.configured = false;
		this.built = false;
		this.buildVersion = 0;
		this.dimension = "";
		this.centerX = 0;
		this.centerY = 0;
		this.centerZ = 0;
		setDirty();
	}

	public boolean isConfigured() {
		return configured;
	}

	public boolean isBuilt() {
		return built;
	}

	public int getBuildVersion() {
		return buildVersion;
	}

	public String getDimension() {
		return dimension;
	}

	public BlockPos getCenter() {
		return new BlockPos(centerX, centerY, centerZ);
	}

	public int getCenterX() {
		return centerX;
	}

	public int getCenterY() {
		return centerY;
	}

	public int getCenterZ() {
		return centerZ;
	}
}
