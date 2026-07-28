package com.nikita.arenaofnations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;

/**
 * Supported arena countries. Persistent storage uses {@link #getId()} string keys only — never enum ordinal.
 */
public enum Country {
	RU("ru", "RU", "Россия", ChatFormatting.RED),
	UA("ua", "UA", "Украина", ChatFormatting.BLUE),
	BY("by", "BY", "Беларусь", ChatFormatting.GREEN),
	KZ("kz", "KZ", "Казахстан", ChatFormatting.AQUA),
	LT("lt", "LT", "Литва", ChatFormatting.YELLOW),
	PL("pl", "PL", "Польша", ChatFormatting.WHITE),
	IL("il", "IL", "Израиль", ChatFormatting.GOLD),
	AM("am", "AM", "Армения", ChatFormatting.DARK_RED),
	UZ("uz", "UZ", "Узбекистан", ChatFormatting.DARK_GREEN),
	TJ("tj", "TJ", "Таджикистан", ChatFormatting.DARK_AQUA),
	GE("ge", "GE", "Грузия", ChatFormatting.DARK_BLUE),
	KG("kg", "KG", "Кыргызстан", ChatFormatting.DARK_PURPLE),
	TM("tm", "TM", "Туркменистан", ChatFormatting.GRAY),
	MD("md", "MD", "Молдова", ChatFormatting.DARK_GRAY),
	AZ("az", "AZ", "Азербайджан", ChatFormatting.LIGHT_PURPLE),
	LV("lv", "LV", "Латвия", ChatFormatting.RED),
	AL("al", "AL", "Албания", ChatFormatting.BLUE),
	BG("bg", "BG", "Болгария", ChatFormatting.GREEN),
	CN("cn", "CN", "Китай", ChatFormatting.GOLD),
	US("us", "US", "США", ChatFormatting.AQUA);

	public static final int SUPPORTED_COUNT = 20;
	public static final List<Country> ALL = Collections.unmodifiableList(Arrays.asList(values()));

	private final String id;
	private final String code;
	private final String displayName;
	private final ChatFormatting color;

	Country(String id, String code, String displayName, ChatFormatting color) {
		this.id = id;
		this.code = code;
		this.displayName = displayName;
		this.color = color;
	}

	public String getId() {
		return id;
	}

	/** Two-letter UI / HUD code (stable, not ordinal). */
	public String getCode() {
		return code;
	}

	public String getDisplayName() {
		return displayName;
	}

	public ChatFormatting getColor() {
		return color;
	}

	public String countryTag() {
		return "country_" + id;
	}

	public String teamName() {
		return "arena_" + id;
	}

	public String flagTexturePath() {
		return "textures/gui/flags/" + id + ".png";
	}

	/** Single-class fighter skin path segment (Боец). */
	public String fighterSkinFileName() {
		return "warrior";
	}

	public String fighterTexturePath() {
		return "textures/entity/fighter/" + id + "/" + fighterSkinFileName() + ".png";
	}

	public static Country byId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (Country country : values()) {
			if (country.id.equals(normalized) || country.code.equalsIgnoreCase(normalized)) {
				return country;
			}
		}
		return null;
	}

	public static List<String> allIds() {
		return ALL.stream().map(Country::getId).toList();
	}
}
