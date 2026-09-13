package com.yuyuframe.launcheragent.apimixin.v1_8_9.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_LOAD} sur 1.8.9 — {@code ClientWorld.onEntitySpawned(Entity)}, TAIL.
 *
 * <p>{@code ClientWorld} redéfinit cette méthode de {@code World} (javap
 * {@code bdb.a(pk)V}) : viser la classe client exclut d'office les mondes du
 * serveur intégré. Yarn legacy ne nomme la méthode que sur {@code World} —
 * l'entrée de refmap y replie.
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld")
public abstract class EntityLoadMixin189 {

    @Inject(method = "onEntitySpawned(Lnet/minecraft/entity/Entity;)V", at = @At("TAIL"), require = 0)
    private void la$dispatchEntityLoad(@Coerce Object entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_LOAD, entity);
    }
}
