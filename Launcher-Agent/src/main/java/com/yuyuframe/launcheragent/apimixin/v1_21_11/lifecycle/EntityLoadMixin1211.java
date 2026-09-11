package com.yuyuframe.launcheragent.apimixin.v1_21_11.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_LOAD} sur 1.21.11 — pendant de {@code EntityLoadMixin261}.
 *
 * <pre>
 *   26.1.2 : ClientLevel$EntityCallbacks.onTrackingStart(Entity), TAIL
 *   1.21.11 : ClientWorld$ClientEntityHandler.startTracking(Entity), TAIL
 * </pre>
 *
 * Le pont synthétique {@code onTrackingStart(Object)} existe aussi (Mojang,
 * officiel {@code c}) : le descripteur {@code (Entity)} vise la vraie méthode.
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld$ClientEntityHandler")
public abstract class EntityLoadMixin1211 {

    @Inject(method = "startTracking(Lnet/minecraft/entity/Entity;)V", at = @At("TAIL"), require = 0)
    private void la$dispatchEntityLoad(@Coerce Object entity, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_LOAD, entity);
    }
}
