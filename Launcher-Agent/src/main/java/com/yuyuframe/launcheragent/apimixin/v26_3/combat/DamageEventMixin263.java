package com.yuyuframe.launcheragent.apimixin.v26_3.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#DAMAGE_EVENT} sur 26.3 — pendant de
 * {@code DamageEventMixin261}, où sont les raisons : RETURN et non TAIL (la
 * méthode sort tôt quand l'entité est inconnue du client), un seul passage
 * sur le fil du jeu, paquet capturé en {@code @Coerce Object} et lu par
 * {@code DamageEventPacketAccessor263}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class DamageEventMixin263 {

    @Inject(method = "handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchDamageEvent(@Coerce Object packet, CallbackInfo ci) {
        DamageEvent event = CombatEvents263.damage(packet);
        if (event != null) {
            VanillaHookRegistry.dispatch(HookPoint.DAMAGE_EVENT, event);
        }
    }
}
