package com.yuyuframe.launcheragent.apimixin.v26_1.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code MinecraftMixin#onStartTick} (fabric-lifecycle-events-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#CLIENT_TICK} — HEAD de
 * {@code Minecraft.tick()}, base de quasi tous les modules à logique
 * périodique. Original Fabric a aussi un END_CLIENT_TICK (RETURN) — non
 * porté ici, à ajouter si un besoin réel de "fin de tick" se présente.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ClientTickMixin261 {

    @Inject(at = @At("HEAD"), method = "tick")
    private void la$dispatchClientTick(CallbackInfo info) {
        VanillaHookRegistry.dispatch(HookPoint.CLIENT_TICK, null);
    }
}
