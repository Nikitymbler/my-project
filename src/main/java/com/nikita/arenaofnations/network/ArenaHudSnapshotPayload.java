package com.nikita.arenaofnations.network;

import java.util.ArrayList;
import java.util.List;

import com.nikita.arenaofnations.ArenaHudCountryState;
import com.nikita.arenaofnations.ArenaHudSnapshot;
import com.nikita.arenaofnations.ArenaMatchState;
import com.nikita.arenaofnations.ArenaOfNations;
import com.nikita.arenaofnations.Country;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ArenaHudSnapshotPayload(ArenaHudSnapshot snapshot) implements CustomPacketPayload {
	public static final Type<ArenaHudSnapshotPayload> TYPE =
			new Type<>(ArenaOfNations.id("round_hud"));
	public static final StreamCodec<FriendlyByteBuf, ArenaHudSnapshotPayload> STREAM_CODEC =
			CustomPacketPayload.codec(ArenaHudSnapshotPayload::write, ArenaHudSnapshotPayload::new);

	public ArenaHudSnapshotPayload {
		snapshot = snapshot == null ? ArenaHudSnapshot.EMPTY : snapshot;
	}

	public ArenaHudSnapshotPayload(FriendlyByteBuf buffer) {
		this(readSnapshot(buffer));
	}

	private void write(FriendlyByteBuf buffer) {
		buffer.writeEnum(snapshot.state());
		buffer.writeVarInt(snapshot.remainingTicks());
		buffer.writeEnum(snapshot.mode());
		buffer.writeVarInt(snapshot.activeCountryCount());
		buffer.writeUtf(snapshot.rescueCountryCode() == null ? "" : snapshot.rescueCountryCode());
		buffer.writeBoolean(snapshot.arenaCenterValid());
		buffer.writeVarInt(snapshot.arenaCenterX());
		buffer.writeVarInt(snapshot.arenaCenterY());
		buffer.writeVarInt(snapshot.arenaCenterZ());
		buffer.writeVarInt(snapshot.countries().size());
		for (ArenaHudCountryState country : snapshot.countries()) {
			buffer.writeUtf(country.country().getId());
			buffer.writeVarInt(country.baseSlot());
			buffer.writeVarInt(country.aliveFighters());
			buffer.writeFloat(country.coreHealth());
			buffer.writeFloat(country.coreMaxHealth());
			buffer.writeVarInt(country.reserveCount());
			buffer.writeBoolean(country.eliminated());
			buffer.writeBoolean(country.rescuing());
			buffer.writeVarInt(Math.max(0, country.rescueSecondsRemaining()));
			buffer.writeBoolean(country.holder());
			buffer.writeBoolean(country.coreProtected());
		}
	}

	private static ArenaHudSnapshot readSnapshot(FriendlyByteBuf buffer) {
		ArenaMatchState state = buffer.readEnum(ArenaMatchState.class);
		int remainingTicks = Math.max(0, buffer.readVarInt());
		var mode = buffer.readEnum(com.nikita.arenaofnations.ArenaHudDisplayMode.class);
		int activeCountryCount = Math.max(0, buffer.readVarInt());
		String rescueCountryCode = buffer.readUtf();
		if (rescueCountryCode.isBlank()) {
			rescueCountryCode = null;
		}
		boolean arenaCenterValid = buffer.readBoolean();
		int arenaCenterX = buffer.readVarInt();
		int arenaCenterY = buffer.readVarInt();
		int arenaCenterZ = buffer.readVarInt();
		int count = Math.max(0, buffer.readVarInt());
		List<ArenaHudCountryState> countries = new ArrayList<>(Math.min(count, Country.values().length));
		for (int index = 0; index < count; index++) {
			Country country = Country.byId(buffer.readUtf());
			int baseSlot = buffer.readVarInt();
			int alive = Math.max(0, buffer.readVarInt());
			float coreHealth = buffer.readFloat();
			float coreMaxHealth = buffer.readFloat();
			int reserve = Math.max(0, buffer.readVarInt());
			boolean eliminated = buffer.readBoolean();
			boolean rescuing = buffer.readBoolean();
			int rescueSeconds = Math.max(0, buffer.readVarInt());
			boolean holder = buffer.readBoolean();
			boolean coreProtected = buffer.readBoolean();
			if (country != null) {
				countries.add(new ArenaHudCountryState(
						country,
						baseSlot,
						alive,
						coreHealth,
						coreMaxHealth,
						reserve,
						eliminated,
						rescuing && !eliminated,
						rescueSeconds,
						holder,
						coreProtected));
			}
		}
		return new ArenaHudSnapshot(
				state,
				remainingTicks,
				mode,
				activeCountryCount,
				rescueCountryCode,
				arenaCenterX,
				arenaCenterY,
				arenaCenterZ,
				arenaCenterValid,
				countries);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
