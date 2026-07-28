package com.nikita.arenaofnations;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public final class FighterFactory {
	public static final String FIGHTER_TAG = "arena_fighter";
	/** Marks that arena combat AI defaults were applied once at spawn. */
	public static final String AI_READY_TAG = "arena_ai_ready";

	/** Must cover the large arena field (search radius 80). */
	private static final double FOLLOW_RANGE = 96.0;

	private FighterFactory() {
	}

	public static ArenaFighterEntity create(ServerLevel level, BlockPos pos, Country country, FighterTier tier) {
		ArenaFighterEntity fighter = ArenaEntities.ARENA_FIGHTER.spawn(level, pos, MobSpawnType.COMMAND);
		if (fighter == null) {
			return null;
		}

		FighterTier singleTier = FighterTier.SCOUT;
		fighter.setArenaData(country, singleTier);
		fighter.setBaby(false);
		fighter.setCustomName(Component.literal(country.getDisplayName() + " — Боец").withStyle(country.getColor()));
		fighter.setCustomNameVisible(true);
		applyStats(fighter, singleTier);
		prepareCombatAi(fighter);
		assignTeam(level, fighter, country);

		return fighter;
	}

	public static Country getCountry(Entity entity) {
		if (entity instanceof ArenaFighterEntity fighter) {
			return fighter.getArenaCountry();
		}
		for (Country country : Country.values()) {
			if (entity.getTags().contains(country.countryTag())) {
				return country;
			}
		}
		return null;
	}

	public static boolean isArenaFighter(Entity entity) {
		return entity instanceof ArenaFighterEntity
				|| entity.getTags().contains(FIGHTER_TAG);
	}

	private static void applyStats(ArenaFighterEntity fighter, FighterTier tier) {
		Objects.requireNonNull(fighter.getAttribute(Attributes.MAX_HEALTH)).setBaseValue(tier.getMaxHealth());
		Objects.requireNonNull(fighter.getAttribute(Attributes.ATTACK_DAMAGE)).setBaseValue(tier.getAttackDamage());
		Objects.requireNonNull(fighter.getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(tier.getMovementSpeed());
		Objects.requireNonNull(fighter.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
				.setBaseValue(tier.getKnockbackResistance());
		Objects.requireNonNull(fighter.getAttribute(Attributes.SCALE)).setBaseValue(tier.getScale());
		Objects.requireNonNull(fighter.getAttribute(Attributes.FOLLOW_RANGE)).setBaseValue(FOLLOW_RANGE);
		fighter.setHealth((float) tier.getMaxHealth());
	}

	/**
	 * Default wolf sit/NoAI would prevent chase. Reset combat-ready state once at spawn.
	 */
	private static void prepareCombatAi(ArenaFighterEntity fighter) {
		fighter.setNoAi(false);
		fighter.setOrderedToSit(false);
		fighter.setInSittingPose(false);
		if (!fighter.getTags().contains(AI_READY_TAG)) {
			fighter.addTag(AI_READY_TAG);
		}
	}

	private static void assignTeam(ServerLevel level, ArenaFighterEntity fighter, Country country) {
		Scoreboard scoreboard = level.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(country.teamName());

		if (team == null) {
			team = scoreboard.addPlayerTeam(country.teamName());
			team.setColor(country.getColor());
			team.setDisplayName(Component.literal(country.getDisplayName()));
		}

		scoreboard.addPlayerToTeam(fighter.getScoreboardName(), team);
	}
}
