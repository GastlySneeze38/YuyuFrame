package com.yuyuframe.launcheragent.apimixin.v26_2.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code ClientLevelEntityCallbacksMixin#invokeLoadEntity}
 * (fabric-lifecycle-events-v1, voir mixinapi/26.1.2) vers {@link HookPoint#ENTITY_LOAD}
 * — {@code targets} pointe la classe imbriquée
 * {@code ClientLevel$EntityCallbacks} (pas besoin du champ {@code this$0}
 * vers le {@code ClientLevel} parent pour une simple notification).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
abstract class EntityLoadMixin262 {

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void la$dispatchEntityLoad(Entity entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_LOAD, entity);
    }
}
