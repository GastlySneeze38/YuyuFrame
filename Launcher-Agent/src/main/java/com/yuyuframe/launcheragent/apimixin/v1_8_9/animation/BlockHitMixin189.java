package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ATTACK_HELD_TICK} sur 1.8.9 —
 * {@code MinecraftClient.handleBlockBreaking(Z)V} ({@code ave.b(Z)V}).
 *
 * <p>Appelée à chaque tick avec « attaque maintenue ». Vanilla en sort tout de
 * suite si le joueur utilise un objet (javap : {@code bew.bS()Z} → return,
 * offset 23) : ni coup ni particules pendant qu'on bloque ou mange — c'est
 * précisément ce que la 1.7 affichait (block-hit). L'injection en tête voit
 * donc chaque tick, avant cette sortie ; le module décide.
 *
 * <p>Même point que {@code sendClickBlockToController} d'Animatium-Legacy
 * (ex-OverflowAnimations, 1.8.9).
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class BlockHitMixin189 {

    @Inject(method = "handleBlockBreaking(Z)V", at = @At("HEAD"), require = 0)
    private void la$attackHeldTick(boolean attackHeld, CallbackInfo ci) {
        try {
            VanillaHookRegistry.dispatch(HookPoint.ATTACK_HELD_TICK, Boolean.valueOf(attackHeld));
        } catch (Throwable t) {
            LauncherLog.err("[BlockHitMixin189] " + t);
        }
    }
}
