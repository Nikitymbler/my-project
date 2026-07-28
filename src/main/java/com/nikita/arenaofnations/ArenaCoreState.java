package com.nikita.arenaofnations;

/**
 * In-memory durability state of one country's core for the current round.
 */
public final class ArenaCoreState {
	private final Country country;
	private float maxHealth;
	private float currentHealth;
	private boolean active;
	private boolean destroyed;

	public ArenaCoreState(Country country, float maxHealth) {
		this.country = country;
		this.maxHealth = Math.max(1.0F, maxHealth);
		this.currentHealth = this.maxHealth;
		this.active = false;
		this.destroyed = false;
	}

	public Country getCountry() {
		return country;
	}

	public float getMaxHealth() {
		return maxHealth;
	}

	public float getCurrentHealth() {
		return currentHealth;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	public float getHealthPercent() {
		if (maxHealth <= 0.0F) {
			return 0.0F;
		}
		return (currentHealth / maxHealth) * 100.0F;
	}

	public void activate(float maxHp) {
		this.maxHealth = Math.max(1.0F, maxHp);
		this.currentHealth = this.maxHealth;
		this.active = true;
		this.destroyed = false;
	}

	public void deactivate() {
		this.active = false;
	}

	public void resetInactive(float maxHp) {
		this.maxHealth = Math.max(1.0F, maxHp);
		this.currentHealth = this.maxHealth;
		this.active = false;
		this.destroyed = false;
	}

	public float damage(float amount) {
		if (amount <= 0.0F) {
			return currentHealth;
		}
		currentHealth = Math.max(0.0F, currentHealth - amount);
		destroyed = currentHealth <= 0.0F;
		return currentHealth;
	}

	public float heal(float amount) {
		if (amount <= 0.0F) {
			return currentHealth;
		}
		currentHealth = Math.min(maxHealth, currentHealth + amount);
		if (currentHealth > 0.0F) {
			destroyed = false;
		}
		return currentHealth;
	}

	/**
	 * Restore a destroyed core after a successful rescue gift.
	 */
	public void restoreToPercent(int percent) {
		int clamped = Math.max(1, Math.min(100, percent));
		currentHealth = Math.max(1.0F, maxHealth * (clamped / 100.0F));
		destroyed = false;
		active = true;
	}

	public void markEliminatedKeepDestroyed() {
		active = false;
		destroyed = true;
		currentHealth = 0.0F;
	}

	public CoreVisual resolveVisual() {
		if (!active && !destroyed) {
			return CoreVisual.INACTIVE;
		}
		if (destroyed || currentHealth <= 0.0F) {
			return CoreVisual.DESTROYED;
		}
		if (getHealthPercent() <= 50.0F) {
			return CoreVisual.DAMAGED;
		}
		return CoreVisual.ACTIVE;
	}

	public enum CoreVisual {
		ACTIVE,
		DAMAGED,
		DESTROYED,
		INACTIVE
	}
}
