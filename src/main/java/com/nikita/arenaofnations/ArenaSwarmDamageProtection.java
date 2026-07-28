package com.nikita.arenaofnations;

import java.util.ArrayDeque;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Limits cumulative incoming damage from lower tiers within a short window.
 */
final class ArenaSwarmDamageProtection {
	static final int WINDOW_TICKS = 20;
	private static final int RECENT_WINDOW_TICKS = 200;

	private static final ArrayDeque<RecentEvent> RECENT_EVENTS = new ArrayDeque<>();

	private ArenaSwarmDamageProtection() {
	}

	static boolean applies(ArenaFighterEntity defender, DamageSource source, float scaledAmount) {
		return resolveContext(defender, source, scaledAmount) != null;
	}

	static float limitDamage(ArenaFighterEntity defender, DamageSource source, float scaledAmount) {
		SwarmContext context = resolveContext(defender, source, scaledAmount);
		if (context == null) {
			return scaledAmount;
		}

		defender.ensureSwarmWindow(context.gameTime());
		int gapIndex = context.gap() - 1;
		float remaining = context.budgetCap() - defender.getSwarmBudgetUsed(gapIndex);
		if (remaining <= 0.0F) {
			defender.recordSwarmSuppressed(context.gap(), scaledAmount);
			defender.maybePlaySwarmExhaustVfx(context.gap());
			return 0.0F;
		}
		return Math.min(scaledAmount, remaining);
	}

	static void onDamageApplied(
			ArenaFighterEntity defender,
			DamageSource source,
			float scaledAmount,
			float allowedAmount,
			float actualDamage) {
		SwarmContext context = resolveContext(defender, source, scaledAmount);
		if (context == null) {
			return;
		}

		if (actualDamage > 0.0F) {
			defender.addSwarmBudgetUsed(context.gap() - 1, actualDamage);
		}

		boolean budgetExhausted = defender.getSwarmBudgetUsed(context.gap() - 1) >= context.budgetCap() - 0.001F;
		if (budgetExhausted && allowedAmount < scaledAmount) {
			defender.maybePlaySwarmExhaustVfx(context.gap());
		}

		float prevented = Math.max(0.0F, scaledAmount - allowedAmount);
		if (prevented > 0.0F) {
			recordRecent(context.gameTime(), prevented, actualDamage <= 0.0F);
		}
	}

	static void playExhaustVfx(ArenaFighterEntity defender, int gap) {
		if (!(defender.level() instanceof ServerLevel level)) {
			return;
		}
		double x = defender.getX();
		double y = defender.getY() + defender.getBbHeight() * 0.55;
		double z = defender.getZ();
		level.sendParticles(ParticleTypes.ENCHANTED_HIT, x, y, z, 4, 0.25, 0.20, 0.25, 0.02);
		level.sendParticles(ParticleTypes.SMOKE, x, y, z, 3, 0.20, 0.15, 0.20, 0.01);
		level.playSound(null, x, defender.getY(), z, SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.45F, 0.82F);
	}

	static RecentStats recentStats(long gameTime) {
		synchronized (RECENT_EVENTS) {
			pruneRecent(gameTime);
			int suppressed = 0;
			float prevented = 0.0F;
			for (RecentEvent event : RECENT_EVENTS) {
				if (event.suppressed()) {
					suppressed++;
				}
				prevented += event.preventedDamage();
			}
			return new RecentStats(suppressed, prevented);
		}
	}

	static String buildSwarmStatusText(ServerLevel level) {
		int protectedDefenders = 0;
		int titanProtected = 0;
		float[] titanUsed = new float[4];
		float[] titanCaps = new float[4];

		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArenaFighterEntity fighter) || !fighter.isAlive()) {
				continue;
			}
			if (!fighter.hasActiveSwarmWindow()) {
				continue;
			}
			protectedDefenders++;
			if (fighter.getArenaTier() != FighterTier.TITAN) {
				continue;
			}
			titanProtected++;
			for (int gap = 1; gap <= 4; gap++) {
				titanUsed[gap - 1] += fighter.getSwarmBudgetUsed(gap - 1);
				titanCaps[gap - 1] += budgetCap(fighter, gap);
			}
		}

		RecentStats recent = recentStats(level.getGameTime());
		return "Swarm Protection (окно "
				+ WINDOW_TICKS
				+ " тиков)\n"
				+ "защитников с активным budget="
				+ protectedDefenders
				+ '\n'
				+ "TITAN с активным budget="
				+ titanProtected
				+ '\n'
				+ "предотвращено урона за ~10с="
				+ format(recent.preventedDamage())
				+ '\n'
				+ "подавлено ударов за ~10с="
				+ recent.suppressedHits()
				+ '\n'
				+ "TITAN budget gap1 used/max="
				+ format(titanUsed[0])
				+ '/'
				+ format(titanCaps[0])
				+ '\n'
				+ "gap2 used/max="
				+ format(titanUsed[1])
				+ '/'
				+ format(titanCaps[1])
				+ '\n'
				+ "gap3 used/max="
				+ format(titanUsed[2])
				+ '/'
				+ format(titanCaps[2])
				+ '\n'
				+ "gap4 used/max="
				+ format(titanUsed[3])
				+ '/'
				+ format(titanCaps[3]);
	}

	private static SwarmContext resolveContext(ArenaFighterEntity defender, DamageSource source, float scaledAmount) {
		if (scaledAmount <= 0.0F || defender.level().isClientSide) {
			return null;
		}

		Entity attackerEntity = source.getEntity();
		if (!(attackerEntity instanceof ArenaFighterEntity attacker)) {
			return null;
		}
		if (!FighterFactory.isArenaFighter(defender) || !FighterFactory.isArenaFighter(attacker)) {
			return null;
		}
		if (attacker == defender) {
			return null;
		}

		Country defenderCountry = defender.getArenaCountry();
		Country attackerCountry = attacker.getArenaCountry();
		if (defenderCountry == null || attackerCountry == null || defenderCountry == attackerCountry) {
			return null;
		}

		FighterTier attackerTier = attacker.getArenaTier();
		FighterTier defenderTier = defender.getArenaTier();
		int gap = defenderTier.ordinal() - attackerTier.ordinal();
		if (gap <= 0) {
			return null;
		}

		long gameTime = defender.level().getGameTime();
		return new SwarmContext(gap, budgetCap(defender, gap), gameTime);
	}

	static float budgetCap(ArenaFighterEntity defender, int gap) {
		float maxHealth = (float) defender.getAttributeValue(Attributes.MAX_HEALTH);
		float rate = switch (Math.min(gap, 4)) {
			case 1 -> 0.08F;
			case 2 -> 0.05F;
			case 3 -> 0.03F;
			default -> 0.015F;
		};
		return maxHealth * rate;
	}

	private static void recordRecent(long gameTime, float preventedDamage, boolean suppressed) {
		synchronized (RECENT_EVENTS) {
			RECENT_EVENTS.addLast(new RecentEvent(gameTime, preventedDamage, suppressed));
			pruneRecent(gameTime);
		}
	}

	private static void pruneRecent(long gameTime) {
		while (!RECENT_EVENTS.isEmpty() && gameTime - RECENT_EVENTS.peekFirst().gameTime() > RECENT_WINDOW_TICKS) {
			RECENT_EVENTS.removeFirst();
		}
	}

	private static String format(float value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	private record SwarmContext(int gap, float budgetCap, long gameTime) {
	}

	private record RecentEvent(long gameTime, float preventedDamage, boolean suppressed) {
	}

	record RecentStats(int suppressedHits, float preventedDamage) {
	}
}
