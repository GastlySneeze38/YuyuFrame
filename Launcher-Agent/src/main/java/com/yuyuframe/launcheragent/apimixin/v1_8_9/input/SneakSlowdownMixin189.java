package com.yuyuframe.launcheragent.apimixin.v1_8_9.input;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SNEAK_SLOWDOWN} sur 1.8.9 — {@code KeyboardInput.tick()V}.
 *
 * <p>Vanilla multiplie les deux composantes du déplacement par la constante
 * {@code 0.3D} quand le joueur est accroupi (javap {@code bev.a()V}, deux
 * {@code ldc2_w}). Ces multiplications ne sont atteintes QUE accroupi : le
 * handler est donc appelé en tête de méthode, à chaque tick, pour qu'un
 * module qui anime ce facteur voie aussi les ticks debout. Les deux constantes
 * reprennent la valeur obtenue.
 *
 * <p>Même découpage que l'ancien {@code MixinSneakRamp189}, qui lisait
 * l'état accroupi par {@code @Shadow} sur le nom obfusqué {@code d} et gardait
 * la courbe dans le mixin : elle vit maintenant dans {@code SneakRampModule}.
 */
@Mixin(targets = "net.minecraft.client.input.KeyboardInput")
public abstract class SneakSlowdownMixin189 {

    private static double la$factor = 0.3d;

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void la$dispatchSlowdown(CallbackInfo ci) {
        boolean sneaking = AccessorRegistry.getBoolean(AccessPoint.PLAYER_INPUT_SNEAKING, null, false);
        Object factor = VanillaHookRegistry.dispatchValue(HookPoint.SNEAK_SLOWDOWN, Boolean.valueOf(sneaking));
        la$factor = factor instanceof Number ? ((Number) factor).doubleValue() : 0.3d;
    }

    @ModifyConstant(method = "tick()V", constant = @Constant(doubleValue = 0.3d), require = 0)
    private double la$applySlowdown(double original) {
        return la$factor;
    }
}
