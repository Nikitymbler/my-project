package com.nikita.arenaofnations;

public final class PendingFighter {
	private final Country country;
	private final FighterTier tier;
	private final int coins;

	public PendingFighter(Country country, FighterTier tier, int coins) {
		this.country = country;
		this.tier = tier;
		this.coins = coins;
	}

	public Country getCountry() {
		return country;
	}

	public FighterTier getTier() {
		return tier;
	}

	public int getCoins() {
		return coins;
	}
}
