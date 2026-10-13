package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_EVENT} — {@code ClientPacketListener.handleEntityEvent}.
 * RETURN et non TAIL, capture en {@code @Coerce Object}, un seul passage sur
 * le fil du jeu : mêmes raisons que {@code DamageEventMixin261}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class EntityEventMixin261 {

    @Inject(method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchEntityEvent(@Coerce Object packet, CallbackInfo ci) {
        EntityEvent event = CombatEvents261.entityEvent(packet);
        if (event != null) {
            VanillaHookRegistry.dispatch(HookPoint.ENTITY_EVENT, event);
        }
    }
}
