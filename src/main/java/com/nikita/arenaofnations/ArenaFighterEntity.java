package com.nikita.arenaofnations;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Arena fighter entity. Still extends {@link Wolf} for combat AI inheritance,
 * but registers a ground melee goal set without wolf leap / pack behaviour.
 */
public class ArenaFighterEntity extends Wolf {
	private static final EntityDataAccessor<String> COUNTRY_ID =
			SynchedEntityData.defineId(ArenaFighterEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> TIER_ID =
			SynchedEntityData.defineId(ArenaFighterEntity.class, EntityDataSerializers.STRING);

	private static final String NBT_COUNTRY = "ArenaCountry";
	private static final String NBT_TIER = "ArenaTier";

	private static final AtomicBoolean LOGGED_GOALS = new AtomicBoolean(false);

	/** ELITE (HEAVY) heavy-strike cycle: 0..2 normal, 3 = next successful hit is heavy. Not saved to NBT. */
	private int eliteHeavyStrikeProgress;
	private int eliteHeavyStrikeTriggerCount;
	private long eliteLastHeavyStrikeGameTime = -1L;
	/** CHAMPION (HERO) vampiric cycle: 0..1 normal, 2 = next successful hit is vampiric. Not saved to NBT. */
	private int championVampiricStrikeProgress;
	private int championVampiricStrikeTriggerCount;
	private long championLastVampiricStrikeGameTime = -1L;
	private float championTotalVampiricHealing;
	/** TITAN shockwave cycle: 0 normal, 1 = next successful hit triggers wave. Not saved to NBT. */
	private int titanShockwaveProgress;
	private int titanShockwaveTriggerCount;
	private long titanLastShockwaveGameTime = -1L;
	private int titanShockwaveSecondaryHitCount;
	private float titanTotalShockwaveDamage;
	/** Swarm damage budget per tier-gap window (not saved to NBT). */
	private long swarmWindowStartGameTime = -1L;
	private final float[] swarmBudgetUsed = new float[4];
	private final boolean[] swarmGapExhaustShown = new boolean[4];
	private int swarmSuppressedHits;
	private float swarmPreventedDamage;
	private final ArenaFighterMeleeStats meleeStats = new ArenaFighterMeleeStats();
	private boolean meleeWindupActive;
	private boolean meleeGoalRunning;
	private long targetAssignedGameTime = -1L;
	/** One-shot death burst; not saved to NBT. */
	private boolean deathFeedbackPlayed;

	public ArenaFighterEntity(EntityType<? extends ArenaFighterEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Wolf.createAttributes();
	}

	/**
	 * Do not call {@code super.registerGoals()}: Wolf adds {@link LeapAtTargetGoal}
	 * (the pounce) plus owner/prey targeting that fights arena targeting.
	 * Arena movement/targeting stays driven by {@link FighterTargeting}.
	 */
	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(1, new FloatGoal(this));
		// Wind-up melee: swing first, damage a few ticks later. No leap.
		this.goalSelector.addGoal(2, new ArenaFighterMeleeAttackGoal(this, 1.0D, true));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
		// Intentionally empty targetSelector: FighterTargeting assigns enemy targets.

		if (!this.level().isClientSide && LOGGED_GOALS.compareAndSet(false, true)) {
			logRegisteredGoals();
		}
	}

	private void logRegisteredGoals() {
		String goals = this.goalSelector.getAvailableGoals().stream()
				.map(WrappedGoal::getGoal)
				.map(goal -> goal.getClass().getSimpleName())
				.collect(Collectors.joining(", "));
		String targets = this.targetSelector.getAvailableGoals().stream()
				.map(WrappedGoal::getGoal)
				.map(goal -> goal.getClass().getSimpleName())
				.collect(Collectors.joining(", "));
		boolean hasLeap = this.goalSelector.getAvailableGoals().stream()
				.map(WrappedGoal::getGoal)
				.anyMatch(goal -> goal instanceof LeapAtTargetGoal);

		ArenaOfNations.LOGGER.info(
				"ArenaFighterEntity goals: [{}] | targetGoals: [{}] | LeapAtTargetGoal present={}",
				goals,
				targets.isEmpty() ? "none" : targets,
				hasLeap);
	}

	/**
	 * Damage only - swing is started once by {@link ArenaFighterMeleeAttackGoal} during WINDUP.
	 * Class abilities wrap exactly one call to {@code super.doHurtTarget}.
	 */
	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (level().isClientSide || amount <= 0.0F) {
			return super.hurt(source, amount);
		}

		Entity attackerEntity = source.getEntity();
		float beforeVitality = getHealth() + getAbsorptionAmount();
		boolean success = super.hurt(source, amount);
		if (success && attackerEntity instanceof ArenaFighterEntity attacker) {
			float actualDamage = Math.max(0.0F, beforeVitality - (getHealth() + getAbsorptionAmount()));
			if (actualDamage > 0.0F) {
				long gameTime = level().getGameTime();
				attacker.getMeleeStats().recordDamagingHit(gameTime);
				ArenaMeleeDiagnostics.onDamagingHit(gameTime);
			}
		}
		return success;
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		return super.doHurtTarget(target);
	}

	/**
	 * Brief readable death cue (particles + short sound). No loot, no balance changes.
	 */
	@Override
	public void die(DamageSource damageSource) {
		playDeathFeedbackOnce();
		super.die(damageSource);
	}

	/** Arena fighters never drop wolf loot / XP items. */
	@Override
	protected void dropAllDeathLoot(ServerLevel level, DamageSource damageSource) {
	}

	@Override
	public boolean shouldDropExperience() {
		return false;
	}

	private void playDeathFeedbackOnce() {
		if (deathFeedbackPlayed || this.level().isClientSide) {
			return;
		}
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		deathFeedbackPlayed = true;
		ArenaCombatSpectacle.onFighterDeath(serverLevel, this);
	}

	public int getEliteHeavyStrikeProgress() {
		return eliteHeavyStrikeProgress;
	}

	public boolean isEliteHeavyStrikeReady() {
		return ArenaEliteHeavyStrikeAbility.isElite(this) && eliteHeavyStrikeProgress >= 3;
	}

	public int getEliteHeavyStrikeTriggerCount() {
		return eliteHeavyStrikeTriggerCount;
	}

	public long getEliteLastHeavyStrikeGameTime() {
		return eliteLastHeavyStrikeGameTime;
	}

	void incrementEliteHeavyStrikeProgress() {
		if (eliteHeavyStrikeProgress < 3) {
			eliteHeavyStrikeProgress++;
		}
	}

	void resetEliteHeavyStrikeProgress() {
		eliteHeavyStrikeProgress = 0;
	}

	void recordEliteHeavyStrike(long gameTime) {
		eliteHeavyStrikeTriggerCount++;
		eliteLastHeavyStrikeGameTime = gameTime;
	}

	public int getChampionVampiricStrikeProgress() {
		return championVampiricStrikeProgress;
	}

	public boolean isChampionVampiricStrikeReady() {
		return ArenaChampionVampiricStrikeAbility.isChampion(this) && championVampiricStrikeProgress >= 2;
	}

	public int getChampionVampiricStrikeTriggerCount() {
		return championVampiricStrikeTriggerCount;
	}

	public long getChampionLastVampiricStrikeGameTime() {
		return championLastVampiricStrikeGameTime;
	}

	public float getChampionTotalVampiricHealing() {
		return championTotalVampiricHealing;
	}

	void incrementChampionVampiricStrikeProgress() {
		if (championVampiricStrikeProgress < 2) {
			championVampiricStrikeProgress++;
		}
	}

	void resetChampionVampiricStrikeProgress() {
		championVampiricStrikeProgress = 0;
	}

	void recordChampionVampiricStrike(long gameTime, float actualHealing) {
		championVampiricStrikeTriggerCount++;
		championLastVampiricStrikeGameTime = gameTime;
		if (actualHealing > 0.0F) {
			championTotalVampiricHealing += actualHealing;
		}
	}

	public int getTitanShockwaveProgress() {
		return titanShockwaveProgress;
	}

	public boolean isTitanShockwaveReady() {
		return ArenaTitanShockwaveAbility.isTitan(this) && titanShockwaveProgress >= 1;
	}

	public int getTitanShockwaveTriggerCount() {
		return titanShockwaveTriggerCount;
	}

	public long getTitanLastShockwaveGameTime() {
		return titanLastShockwaveGameTime;
	}

	public int getTitanShockwaveSecondaryHitCount() {
		return titanShockwaveSecondaryHitCount;
	}

	public float getTitanTotalShockwaveDamage() {
		return titanTotalShockwaveDamage;
	}

	void incrementTitanShockwaveProgress() {
		if (titanShockwaveProgress < 1) {
			titanShockwaveProgress++;
		}
	}

	void resetTitanShockwaveProgress() {
		titanShockwaveProgress = 0;
	}

	void recordTitanShockwave(long gameTime, int secondaryHits, float actualDamage) {
		titanShockwaveTriggerCount++;
		titanLastShockwaveGameTime = gameTime;
		if (secondaryHits > 0) {
			titanShockwaveSecondaryHitCount += secondaryHits;
		}
		if (actualDamage > 0.0F) {
			titanTotalShockwaveDamage += actualDamage;
		}
	}

	void ensureSwarmWindow(long gameTime) {
		if (swarmWindowStartGameTime < 0L || gameTime - swarmWindowStartGameTime >= ArenaSwarmDamageProtection.WINDOW_TICKS) {
			swarmWindowStartGameTime = gameTime;
			java.util.Arrays.fill(swarmBudgetUsed, 0.0F);
			java.util.Arrays.fill(swarmGapExhaustShown, false);
		}
	}

	boolean hasActiveSwarmWindow() {
		if (swarmWindowStartGameTime < 0L || level().isClientSide) {
			return false;
		}
		return level().getGameTime() - swarmWindowStartGameTime < ArenaSwarmDamageProtection.WINDOW_TICKS;
	}

	float getSwarmBudgetUsed(int gapIndex) {
		if (gapIndex < 0 || gapIndex >= swarmBudgetUsed.length) {
			return 0.0F;
		}
		return swarmBudgetUsed[gapIndex];
	}

	void addSwarmBudgetUsed(int gapIndex, float amount) {
		if (gapIndex < 0 || gapIndex >= swarmBudgetUsed.length || amount <= 0.0F) {
			return;
		}
		swarmBudgetUsed[gapIndex] += amount;
	}

	void recordSwarmSuppressed(int gap, float scaledAmount) {
		swarmSuppressedHits++;
		swarmPreventedDamage += Math.max(0.0F, scaledAmount);
	}

	void maybePlaySwarmExhaustVfx(int gap) {
		int index = gap - 1;
		if (index < 0 || index >= swarmGapExhaustShown.length || swarmGapExhaustShown[index]) {
			return;
		}
		swarmGapExhaustShown[index] = true;
		ArenaSwarmDamageProtection.playExhaustVfx(this, gap);
	}

	public int getSwarmSuppressedHits() {
		return swarmSuppressedHits;
	}

	public float getSwarmPreventedDamage() {
		return swarmPreventedDamage;
	}

	public ArenaFighterMeleeStats getMeleeStats() {
		return meleeStats;
	}

	public void resetMeleeStats() {
		meleeStats.reset();
	}

	public boolean isMeleeWindupActive() {
		return meleeWindupActive;
	}

	void setMeleeWindupActive(boolean active) {
		this.meleeWindupActive = active;
	}

	public boolean isMeleeGoalRunning() {
		return meleeGoalRunning;
	}

	void setMeleeGoalRunning(boolean running) {
		this.meleeGoalRunning = running;
	}

	public long getTargetAssignedGameTime() {
		return targetAssignedGameTime;
	}

	void setTargetAssignedGameTime(long gameTime) {
		this.targetAssignedGameTime = gameTime;
	}

	/**
	 * Vanilla PlayerModel treats the main arm as the swinging arm.
	 * Always right so sword swings match a Steve player.
	 */
	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}

	/**
	 * Vanilla {@link Player} advances hand-swing via {@code updateSwingTime()} in
	 * {@code serverAiStep} / client tick. {@link Wolf} never calls it, so
	 * {@code attackAnim} stays 0 and {@link net.minecraft.client.model.PlayerModel}
	 * never plays the humanoid arm swing after {@link #swing}.
	 */
	@Override
	public void aiStep() {
		super.aiStep();
		this.updateSwingTime();
		// Belt-and-suspenders: cancel residual upward leap velocity if any leap goal slips in.
		if (!this.level().isClientSide && this.getTarget() instanceof LivingEntity && this.getDeltaMovement().y > 0.35D) {
			boolean leaping = this.goalSelector.getAvailableGoals().stream()
					.anyMatch(wrapped -> wrapped.isRunning() && wrapped.getGoal() instanceof LeapAtTargetGoal);
			if (leaping) {
				this.setDeltaMovement(this.getDeltaMovement().x, 0.0D, this.getDeltaMovement().z);
			}
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(COUNTRY_ID, Country.RU.getId());
		builder.define(TIER_ID, FighterTier.SCOUT.getId());
	}

	public void setArenaData(Country country, FighterTier tier) {
		Country resolvedCountry = country != null ? country : Country.RU;
		FighterTier resolvedTier = tier != null ? tier : FighterTier.SCOUT;

		entityData.set(COUNTRY_ID, resolvedCountry.getId());
		entityData.set(TIER_ID, resolvedTier.getId());

		addTag(FighterFactory.FIGHTER_TAG);
		for (Country value : Country.values()) {
			removeTag(value.countryTag());
		}
		for (FighterTier value : FighterTier.values()) {
			removeTag(value.tierTag());
		}
		addTag(resolvedCountry.countryTag());
		addTag(resolvedTier.tierTag());
	}

	public Country getArenaCountry() {
		Country fromData = Country.byId(entityData.get(COUNTRY_ID));
		if (fromData != null) {
			return fromData;
		}
		for (Country country : Country.values()) {
			if (getTags().contains(country.countryTag())) {
				return country;
			}
		}
		return Country.RU;
	}

	/** Synced country id string without lookup — for client cache keys. */
	public String getSyncedCountryId() {
		return entityData.get(COUNTRY_ID);
	}

	/** Synced tier id string without lookup — for client cache keys. */
	public String getSyncedTierId() {
		return entityData.get(TIER_ID);
	}

	public FighterTier getArenaTier() {
		FighterTier fromData = FighterTier.byId(entityData.get(TIER_ID));
		if (fromData != null) {
			return fromData;
		}
		for (FighterTier tier : FighterTier.values()) {
			if (getTags().contains(tier.tierTag())) {
				return tier;
			}
		}
		return FighterTier.SCOUT;
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString(NBT_COUNTRY, getArenaCountry().getId());
		tag.putString(NBT_TIER, getArenaTier().getId());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);

		Country country = null;
		FighterTier tier = null;

		try {
			if (tag.contains(NBT_COUNTRY)) {
				country = Country.byId(tag.getString(NBT_COUNTRY));
			}
			if (tag.contains(NBT_TIER)) {
				tier = FighterTier.byId(tag.getString(NBT_TIER));
			}
		} catch (Exception ignored) {
			country = null;
			tier = null;
		}

		if (country == null) {
			for (Country value : Country.values()) {
				if (getTags().contains(value.countryTag())) {
					country = value;
					break;
				}
			}
		}
		if (tier == null) {
			for (FighterTier value : FighterTier.values()) {
				if (getTags().contains(value.tierTag())) {
					tier = value;
					break;
				}
			}
		}

		if (country == null) {
			country = Country.RU;
		}
		if (tier == null) {
			tier = FighterTier.SCOUT;
		}

		entityData.set(COUNTRY_ID, country.getId());
		entityData.set(TIER_ID, tier.getId());
		addTag(FighterFactory.FIGHTER_TAG);
		addTag(country.countryTag());
		addTag(tier.tierTag());
	}
}
