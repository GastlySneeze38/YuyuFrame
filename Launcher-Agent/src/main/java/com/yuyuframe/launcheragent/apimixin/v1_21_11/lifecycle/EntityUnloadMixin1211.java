package com.yuyuframe.launcheragent.apimixin.v1_21_11.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_UNLOAD} sur 1.21.11 — pendant de
 * {@code EntityUnloadMixin261} : {@code ClientWorld$ClientEntityHandler.stopTracking(Entity)},
 * HEAD. Voir {@link EntityLoadMixin1211} pour le pont synthétique écarté.
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld$ClientEntityHandler")
public abstract class EntityUnloadMixin1211 {

    @Inject(method = "stopTracking(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), require = 0)
    private void la$dispatchEntityUnload(@Coerce Object entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_UNLOAD, entity);
    }
}
