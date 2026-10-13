package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#DAMAGE_EVENT} — {@code ClientPacketListener.handleDamageEvent}.
 *
 * <p><b>RETURN et non TAIL.</b> La méthode sort tôt quand l'entité touchée
 * n'est pas connue du client (relu dans le bytecode 26.1.2) ; TAIL ne vise
 * que le dernier {@code return} et manquerait ce cas. Le journal de combat
 * veut le paquet dans tous les cas.
 *
 * <p><b>Un seul passage, sur le fil du jeu.</b> La méthode commence par
 * {@code PacketUtils.ensureRunningOnSameThread}, qui sur le fil réseau LÈVE
 * pour reprogrammer le paquet : ce premier passage n'atteint aucun
 * {@code return}.
 *
 * <p>Le paquet est capturé en {@code @Coerce Object} : pas de stub à écrire,
 * ses champs se lisent par {@code DamageEventPacketAccessor261}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class DamageEventMixin261 {

    @Inject(method = "handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchDamageEvent(@Coerce Object packet, CallbackInfo ci) {
        DamageEvent event = CombatEvents261.damage(packet);
        if (event != null) {
            VanillaHookRegistry.dispatch(HookPoint.DAMAGE_EVENT, event);
        }
    }
}
