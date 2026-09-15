package com.yuyuframe.launcheragent.apimixin.v1_8_9.input;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#SPRINT_KEY_HELD} sur 1.8.9 — {@code ClientPlayerEntity.tickMovement()V}.
 *
 * <p>Les deux seules lectures de touche de la méthode sont celles du sprint
 * (javap {@code bew.m()V}, offsets 656 et 727 : relance après double appui, et
 * démarrage du sprint) — aucun ordinal, les deux sont substituées. Remplace
 * l'ancien {@code MixinToggleSprint189} (écriture réflexive de {@code pressed}).
 *
 * <p>Sélecteur hérité : Yarn ne nomme {@code tickMovement} que sur
 * {@code LivingEntity} (repli dans le refmap).
 */
@Mixin(targets = "net.minecraft.entity.player.ClientPlayerEntity")
public abstract class SprintKeyHeldMixin189 {

    @ModifyExpressionValue(method = "tickMovement()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"),
        require = 0)
    private boolean la$dispatchSprintHeld(boolean real) {
        Object held = VanillaHookRegistry.dispatchValue(HookPoint.SPRINT_KEY_HELD, Boolean.valueOf(real));
        return held instanceof Boolean ? (Boolean) held : real;
    }
}
