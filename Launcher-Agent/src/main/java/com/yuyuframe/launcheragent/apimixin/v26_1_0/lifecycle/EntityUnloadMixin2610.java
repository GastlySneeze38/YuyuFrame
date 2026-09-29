package com.yuyuframe.launcheragent.apimixin.v26_1_0.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code ClientLevelEntityCallbacksMixin#invokeUnloadEntity}
 * (fabric-lifecycle-events-v1, voir mixinapi/26.1.2) vers {@link HookPoint#ENTITY_UNLOAD}
 * — HEAD (avant que vanilla ne décharge réellement l'entité), voir
 * {@link EntityLoadMixin2610} pour le reste du contexte.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
abstract class EntityUnloadMixin2610 {

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void la$dispatchEntityUnload(Entity entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_UNLOAD, entity);
    }
}
