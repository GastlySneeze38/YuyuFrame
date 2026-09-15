package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#SWING_DURATION} sur 1.8.9 — {@code LivingEntity.n()I}, privée.
 *
 * <p>PIÈGE DE NOM : Yarn legacy l'appelle {@code getMiningSpeedMultiplier}, mais
 * c'est bien la durée du swing (bytecode {@code pr.n()I} : 6, moins le niveau de
 * Célérité, plus deux fois celui de Fatigue). {@code getHandSwingProgress}
 * divise par elle : la raccourcir accélère toute l'animation, bras et objet
 * tenu. Même point que l'ancien {@code MixinSwingSpeed189}.
 */
@Mixin(targets = "net.minecraft.entity.LivingEntity")
public abstract class SwingDurationMixin189 {

    @Inject(method = "getMiningSpeedMultiplier()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$dispatchSwingDuration(CallbackInfoReturnable<Integer> cir) {
        Object duration = VanillaHookRegistry.dispatchValue(HookPoint.SWING_DURATION, cir.getReturnValue());
        if (duration instanceof Integer) cir.setReturnValue((Integer) duration);
    }
}
