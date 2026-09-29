package com.yuyuframe.launcheragent.apimixin.v26_3.clock;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.ClientClockManagerWorldTimeMixin261}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — pourquoi le
 * nombre total de ticks d'horloge est LA source unique dont dérive tout le
 * rendu temporel) vers {@link HookPoint#CLOCK_TOTAL_TICKS}. Contrairement aux
 * autres migrations de cette session, ce hook a besoin de REMPLACER une
 * valeur de retour, pas juste d'annuler un rendu — dispatché via {@link
 * VanillaHookRegistry#dispatchValue}, pas {@link VanillaHookRegistry#dispatch}
 * (voir sa javadoc).
 *
 * <p>26.3 : {@code ClientClockManager.getTotalTicks(Holder)} a disparu —
 * {@code ClockManager} ne rend plus qu'une instance par horloge
 * ({@code getInstance(Holder)}), et c'est {@code ClientClockInstance.totalTicks()}
 * que le rendu lit (javap). Même effet qu'en 26.2 : toutes les horloges du
 * client sont remplacées. Le contexte passé au registre est l'instance au
 * lieu du {@code Holder} — le seul abonné ({@code WorldTimeModule}) l'ignore.
 */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
public abstract class ClockTotalTicksMixin263 {

    @Inject(method = "totalTicks()J", at = @At("HEAD"), cancellable = true)
    private void la$dispatchTotalTicks(CallbackInfoReturnable<Long> cir) {
        Object replacement = VanillaHookRegistry.dispatchValue(HookPoint.CLOCK_TOTAL_TICKS, this);
        if (replacement instanceof Long) {
            cir.setReturnValue((Long) replacement);
        }
    }
}
