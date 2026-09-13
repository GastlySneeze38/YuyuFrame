package com.yuyuframe.launcheragent.apimixin.v1_8_9.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_UNLOAD} sur 1.8.9 — {@code ClientWorld.onEntityRemoved(Entity)},
 * TAIL. Même raisonnement que {@code EntityLoadMixin189} (redéfinition javap
 * {@code bdb.b(pk)V}, refmap repliée sur {@code World}).
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld")
public abstract class EntityUnloadMixin189 {

    @Inject(method = "onEntityRemoved(Lnet/minecraft/entity/Entity;)V", at = @At("TAIL"), require = 0)
    private void la$dispatchEntityUnload(@Coerce Object entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_UNLOAD, entity);
    }
}
