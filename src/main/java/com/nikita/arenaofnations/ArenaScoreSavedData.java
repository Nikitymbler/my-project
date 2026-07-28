package com.nikita.arenaofnations;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent per-world country scores, stored in the overworld DimensionDataStorage
 * as {@code data/arena_of_nations_scores.dat}.
 */
public final class ArenaScoreSavedData extends SavedData {
	public static final String DATA_NAME = "arena_of_nations_scores";

	public static final Factory<ArenaScoreSavedData> FACTORY = new Factory<>(
			ArenaScoreSavedData::new,
			ArenaScoreSavedData::load,
			null);

	private final Map<Country, Integer> scores = new EnumMap<>(Country.class);

	public ArenaScoreSavedData() {
		for (Country country : Country.values()) {
			scores.put(country, 0);
		}
	}

	public static ArenaScoreSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		ArenaScoreSavedData data = new ArenaScoreSavedData();

		for (Country country : Country.values()) {
			String key = country.getId();
			if (tag.contains(key)) {
				data.scores.put(country, tag.getInt(key));
			}
		}

		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		for (Country country : Country.values()) {
			tag.putInt(country.getId(), scores.getOrDefault(country, 0));
		}
		return tag;
	}

	public int getScore(Country country) {
		return scores.getOrDefault(country, 0);
	}

	public int addPoints(Country country, int points) {
		int updated = getScore(country) + points;
		scores.put(country, updated);
		setDirty();
		return updated;
	}

	public void resetAll() {
		for (Country country : Country.values()) {
			scores.put(country, 0);
		}
		setDirty();
	}
}
