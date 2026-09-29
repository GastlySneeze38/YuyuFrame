package com.yuyuframe.launcheragent.apimixin.v26_1_1.clock;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.ClientClockManagerWorldTimeMixin261}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — pourquoi {@code
 * getTotalTicks(Holder)} est LA source unique dont dérive tout le rendu
 * temporel) vers {@link HookPoint#CLOCK_TOTAL_TICKS}. Contrairement aux
 * autres migrations de cette session, ce hook a besoin de REMPLACER une
 * valeur de retour, pas juste d'annuler un rendu — dispatché via {@link
 * VanillaHookRegistry#dispatchValue}, pas {@link VanillaHookRegistry#dispatch}
 * (voir sa javadoc).
 */
@Mixin(targets = "net.minecraft.client.ClientClockManager")
public abstract class ClockTotalTicksMixin2611 {

    @Inject(method = "getTotalTicks(Lnet/minecraft/core/Holder;)J", at = @At("HEAD"), cancellable = true)
    private void la$dispatchTotalTicks(Holder holder, CallbackInfoReturnable<Long> cir) {
        Object replacement = VanillaHookRegistry.dispatchValue(HookPoint.CLOCK_TOTAL_TICKS, holder);
        if (replacement instanceof Long) {
            cir.setReturnValue((Long) replacement);
        }
    }
}
