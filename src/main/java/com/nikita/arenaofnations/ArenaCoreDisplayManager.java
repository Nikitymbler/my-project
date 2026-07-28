package com.nikita.arenaofnations;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;

/**
 * Large world-space base/core labels — one TextDisplay per active base slot.
 */
public final class ArenaCoreDisplayManager {
	public static final String DISPLAY_TAG = "arena_of_nations.core_display";

	private static final ArenaCoreDisplayManager INSTANCE = new ArenaCoreDisplayManager();
	private static final float DISPLAY_SCALE = 2.4F;
	private static final float VIEW_RANGE = 128.0F;

	private final Map<Integer, UUID> slotEntityIds = new HashMap<>();
	private final Map<Integer, DisplayState> lastStates = new HashMap<>();

	private ArenaCoreDisplayManager() {
	}

	public static ArenaCoreDisplayManager get() {
		return INSTANCE;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) {
				return;
			}
			if (ArenaMatchManager.get().getState() != ArenaMatchState.BATTLE
					&& ArenaMatchManager.get().getState() != ArenaMatchState.WAITING_FOR_OPPONENT) {
				return;
			}
			INSTANCE.tickRescueLabels(server);
		});
	}

	public void updateForCountry(ServerLevel level, BlockPos arenaCenter, Country country) {
		// Legacy TextDisplay labels disabled — world markers are rendered client-side
		// by ArenaBaseMarkerRenderer. Keep clear/hide paths for leftover entities.
		int slot = ArenaMatchManager.get().getBaseSlot(country);
		if (slot >= 0) {
			hideSlot(level, slot);
		}
	}

	public void hideSlot(ServerLevel level, int slot) {
		lastStates.remove(slot);
		UUID existing = slotEntityIds.remove(slot);
		if (existing != null && level != null) {
			var entity = level.getEntity(existing);
			if (entity != null) {
				entity.discard();
			}
		}
	}

	public void clearAll(ServerLevel level, BlockPos arenaCenter) {
		for (int slot = 0; slot < ArenaCountryBaseLayout.BASE_SLOT_COUNT; slot++) {
			hideSlot(level, slot);
		}
		lastStates.clear();
	}

	public void refreshActiveCountries(ServerLevel level, BlockPos arenaCenter) {
		clearAll(level, arenaCenter);
		// Client ArenaBaseMarkerRenderer uses HUD snapshot — no TextDisplay spawn.
	}

	public boolean hasDisplay(int slot) {
		return slotEntityIds.containsKey(slot);
	}

	public BlockPos displayPosition(BlockPos arenaCenter, int slot) {
		BlockPos core = ArenaCountryBaseLayout.visualCorePosition(arenaCenter, slot);
		return core.relative(ArenaCountryBaseLayout.inwardDirection(slot), 2).above(8);
	}

	private void tickRescueLabels(MinecraftServer server) {
		ServerLevel level = ArenaBuildManager.resolveArenaLevel(server);
		ArenaSetupSavedData setup = ArenaSetupSavedData.get(server);
		if (level == null || setup == null || !setup.isConfigured()) {
			return;
		}
		BlockPos center = setup.getCenter();
		for (Country country : ArenaMatchManager.get().getCurrentRoundCountries()) {
			if (ArenaCoreRescueManager.get().isRescuing(country)) {
				lastStates.remove(ArenaMatchManager.get().getBaseSlot(country));
				updateForCountry(level, center, country);
			}
		}
	}

	private void spawnOrUpdate(ServerLevel level, BlockPos arenaCenter, int slot, Country country, DisplayState state) {
		hideSlot(level, slot);

		BlockPos labelPos = displayPosition(arenaCenter, slot);
		Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
		if (display == null) {
			return;
		}

		display.moveTo(labelPos.getX() + 0.5D, labelPos.getY(), labelPos.getZ() + 0.5D, 0.0F, 0.0F);
		applyDisplayTag(display, level, buildText(country, state));
		display.addTag(DISPLAY_TAG);
		display.addTag("arena_of_nations.arena_entity");

		level.addFreshEntity(display);
		slotEntityIds.put(slot, display.getUUID());
	}

	private static void applyDisplayTag(Display.TextDisplay display, ServerLevel level, Component text) {
		CompoundTag tag = new CompoundTag();
		tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
		tag.putInt("line_width", 220);
		tag.putInt("background", 0xC0101010);
		tag.putBoolean("shadow", true);
		tag.putBoolean("see_through", false);
		tag.putBoolean("default_background", true);
		tag.putString("billboard", "center");
		tag.putFloat("view_range", VIEW_RANGE);
		tag.putFloat("shadow_radius", 0.5F);
		tag.putFloat("shadow_strength", 0.5F);

		ListTag scale = new ListTag();
		scale.add(FloatTag.valueOf(DISPLAY_SCALE));
		scale.add(FloatTag.valueOf(DISPLAY_SCALE));
		scale.add(FloatTag.valueOf(DISPLAY_SCALE));
		CompoundTag transformation = new CompoundTag();
		transformation.put("scale", scale);
		tag.put("transformation", transformation);

		display.load(tag);
	}

	private static Component buildText(Country country, DisplayState state) {
		MutableComponent title = Component.literal(country.getDisplayName().toUpperCase(Locale.ROOT))
				.withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(true));
		MutableComponent code = Component.literal(" [" + country.getCode() + "]")
				.withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true));
		MutableComponent hp = Component.literal("\nЯДРО " + state.coreHp + " / " + state.coreMaxHp)
				.withStyle(style -> style.withColor(hpColor(state.corePercent)).withBold(true));
		MutableComponent status = Component.literal("\n" + state.statusLabel)
				.withStyle(style -> style.withColor(statusColor(state.statusKind)).withBold(true));
		return title.append(code).append(hp).append(status);
	}

	private static DisplayState buildState(ServerLevel level, Country country) {
		ArenaCoreState core = ArenaCoreManager.get().getState(country);
		float maxHp = Math.max(1.0F, core.getMaxHealth());
		int percent = Math.round((core.getCurrentHealth() / maxHp) * 100.0F);
		String statusLabel;
		StatusKind kind;
		if (ArenaCoreRescueManager.get().isEliminated(country)) {
			statusLabel = "ВЫБЫЛА";
			kind = StatusKind.ELIMINATED;
		} else if (ArenaCoreRescueManager.get().isRescuing(country)) {
			int seconds = ArenaCoreRescueManager.get().getRescueRemainingSeconds(level.getServer(), country);
			statusLabel = "СПАСЕНИЕ " + seconds + "с";
			kind = StatusKind.RESCUE;
		} else if (core.getCurrentHealth() <= 0.0F) {
			statusLabel = "ЯДРО СБИТО";
			kind = StatusKind.VULNERABLE;
		} else if (ArenaCoreManager.get().isCoreProtected(level, country)) {
			statusLabel = "ЩИТ";
			kind = StatusKind.PROTECTED;
		} else {
			statusLabel = "УЯЗВИМА";
			kind = StatusKind.VULNERABLE;
		}
		return new DisplayState(
				Math.round(core.getCurrentHealth()),
				Math.round(maxHp),
				Math.max(0, Math.min(100, percent)),
				statusLabel,
				kind);
	}

	private static int hpColor(int percent) {
		if (percent > 60) {
			return 0x55FF55;
		}
		if (percent >= 30) {
			return 0xFFDD33;
		}
		return 0xFF4444;
	}

	private static int statusColor(StatusKind kind) {
		return switch (kind) {
			case PROTECTED -> 0x66CCFF;
			case VULNERABLE -> 0xFF5555;
			case RESCUE -> 0xFFDD33;
			case ELIMINATED -> 0xAAAAAA;
		};
	}

	private enum StatusKind {
		PROTECTED,
		VULNERABLE,
		RESCUE,
		ELIMINATED
	}

	private record DisplayState(int coreHp, int coreMaxHp, int corePercent, String statusLabel, StatusKind statusKind) {
	}
}
