package com.nikita.arenaofnations;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent per-world country scores, round-win counts, and fighter-round record.
 * Stored in overworld DimensionDataStorage as {@code data/arena_of_nations_scores.dat}.
 */
public final class ArenaScoreSavedData extends SavedData {
	public static final String DATA_NAME = "arena_of_nations_scores";
	public static final String ROUND_WINS_KEY = "roundWins";
	public static final String FIGHTER_RECORD_COUNTRY_KEY = "fighterRoundRecordCountry";
	public static final String FIGHTER_RECORD_COUNT_KEY = "fighterRoundRecordCount";

	public static final Factory<ArenaScoreSavedData> FACTORY = new Factory<>(
			ArenaScoreSavedData::new,
			ArenaScoreSavedData::load,
			null);

	private final Map<Country, Integer> scores = new EnumMap<>(Country.class);
	private final Map<Country, Integer> roundWins = new EnumMap<>(Country.class);
	private String fighterRoundRecordCountryId = "";
	private int fighterRoundRecordCount = 0;

	public ArenaScoreSavedData() {
		for (Country country : Country.values()) {
			scores.put(country, 0);
			roundWins.put(country, 0);
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

		if (tag.contains(ROUND_WINS_KEY)) {
			CompoundTag winsTag = tag.getCompound(ROUND_WINS_KEY);
			for (Country country : Country.values()) {
				String key = country.getId();
				if (winsTag.contains(key)) {
					data.roundWins.put(country, Math.max(0, winsTag.getInt(key)));
				}
			}
		}

		if (tag.contains(FIGHTER_RECORD_COUNTRY_KEY)) {
			data.fighterRoundRecordCountryId = tag.getString(FIGHTER_RECORD_COUNTRY_KEY);
		}
		if (tag.contains(FIGHTER_RECORD_COUNT_KEY)) {
			data.fighterRoundRecordCount = Math.max(0, tag.getInt(FIGHTER_RECORD_COUNT_KEY));
		}
		if (data.fighterRoundRecordCount <= 0) {
			data.fighterRoundRecordCountryId = "";
			data.fighterRoundRecordCount = 0;
		} else if (Country.byId(data.fighterRoundRecordCountryId) == null) {
			data.fighterRoundRecordCountryId = "";
			data.fighterRoundRecordCount = 0;
		}

		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		for (Country country : Country.values()) {
			tag.putInt(country.getId(), scores.getOrDefault(country, 0));
		}
		CompoundTag winsTag = new CompoundTag();
		for (Country country : Country.values()) {
			winsTag.putInt(country.getId(), roundWins.getOrDefault(country, 0));
		}
		tag.put(ROUND_WINS_KEY, winsTag);
		tag.putString(FIGHTER_RECORD_COUNTRY_KEY,
				fighterRoundRecordCountryId == null ? "" : fighterRoundRecordCountryId);
		tag.putInt(FIGHTER_RECORD_COUNT_KEY, Math.max(0, fighterRoundRecordCount));
		return tag;
	}

	public int getScore(Country country) {
		return scores.getOrDefault(country, 0);
	}

	public int getRoundWins(Country country) {
		return roundWins.getOrDefault(country, 0);
	}

	public int addPoints(Country country, int points) {
		int updated = getScore(country) + points;
		scores.put(country, updated);
		setDirty();
		return updated;
	}

	public int addRoundWin(Country country) {
		int updated = getRoundWins(country) + 1;
		roundWins.put(country, updated);
		setDirty();
		return updated;
	}

	public String getFighterRoundRecordCountryId() {
		return fighterRoundRecordCountryId == null ? "" : fighterRoundRecordCountryId;
	}

	public int getFighterRoundRecordCount() {
		return Math.max(0, fighterRoundRecordCount);
	}

	public Country getFighterRoundRecordCountry() {
		if (fighterRoundRecordCount <= 0 || fighterRoundRecordCountryId == null || fighterRoundRecordCountryId.isBlank()) {
			return null;
		}
		return Country.byId(fighterRoundRecordCountryId);
	}

	/**
	 * Updates persistent record only when {@code count} is strictly greater than the saved record.
	 * @return true if the record changed
	 */
	public boolean tryUpdateFighterRoundRecord(Country country, int count) {
		if (country == null || count <= 0) {
			return false;
		}
		if (count > fighterRoundRecordCount) {
			fighterRoundRecordCountryId = country.getId();
			fighterRoundRecordCount = count;
			setDirty();
			return true;
		}
		return false;
	}

	public void resetScorePoints() {
		for (Country country : Country.values()) {
			scores.put(country, 0);
		}
		setDirty();
	}

	public void resetRoundWins() {
		for (Country country : Country.values()) {
			roundWins.put(country, 0);
		}
		setDirty();
	}

	public void resetFighterRoundRecord() {
		fighterRoundRecordCountryId = "";
		fighterRoundRecordCount = 0;
		setDirty();
	}

	public void resetAll() {
		resetScorePoints();
		resetRoundWins();
		resetFighterRoundRecord();
	}
}
