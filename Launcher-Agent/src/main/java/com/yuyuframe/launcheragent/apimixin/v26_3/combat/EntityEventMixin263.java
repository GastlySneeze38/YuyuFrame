package com.yuyuframe.launcheragent.apimixin.v26_3.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_EVENT} sur 26.3 — pendant de
 * {@code EntityEventMixin261} : {@code ClientPacketListener.handleEntityEvent},
 * à chaque sortie, paquet capturé en {@code @Coerce Object}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class EntityEventMixin263 {

    @Inject(method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchEntityEvent(@Coerce Object packet, CallbackInfo ci) {
        EntityEvent event = CombatEvents263.entityEvent(packet);
        if (event != null) {
            VanillaHookRegistry.dispatch(HookPoint.ENTITY_EVENT, event);
        }
    }
}
