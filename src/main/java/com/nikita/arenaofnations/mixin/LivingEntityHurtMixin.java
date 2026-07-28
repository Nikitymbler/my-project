package com.nikita.arenaofnations.mixin;

import java.util.ArrayDeque;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nikita.arenaofnations.ArenaDamageTracker;
import com.nikita.arenaofnations.Country;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Measures real HP+absorption loss around {@link LivingEntity#hurt(DamageSource, float)}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {
	@Unique
	private static final ThreadLocal<ArrayDeque<HurtFrame>> ARENA$FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

	@Inject(method = "hurt", at = @At("HEAD"))
	private void arena$onHurtHead(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;

		if (!(self.level() instanceof ServerLevel)) {
			ARENA$FRAMES.get().push(HurtFrame.ignored());
			return;
		}

		Country attackerCountry = ArenaDamageTracker.resolveAttackerCountry(self, source);
		if (attackerCountry == null) {
			ARENA$FRAMES.get().push(HurtFrame.ignored());
			return;
		}

		ARENA$FRAMES.get().push(new HurtFrame(true, ArenaDamageTracker.totalVitality(self), attackerCountry));
	}

	@Inject(method = "hurt", at = @At("RETURN"))
	private void arena$onHurtReturn(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		ArrayDeque<HurtFrame> stack = ARENA$FRAMES.get();
		if (stack.isEmpty()) {
			return;
		}

		HurtFrame frame = stack.pop();
		if (!frame.track) {
			return;
		}

		if (!Boolean.TRUE.equals(cir.getReturnValue())) {
			return;
		}

		LivingEntity self = (LivingEntity) (Object) this;
		float lost = frame.vitalityBefore - ArenaDamageTracker.totalVitality(self);
		ArenaDamageTracker.creditDamage(frame.attackerCountry, lost);
	}

	@Unique
	private record HurtFrame(boolean track, float vitalityBefore, Country attackerCountry) {
		static HurtFrame ignored() {
			return new HurtFrame(false, 0.0F, null);
		}
	}
}
