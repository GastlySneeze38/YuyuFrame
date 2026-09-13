package com.yuyuframe.launcheragent.apimixin.v1_8_9.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CLIENT_TICK} sur 1.8.9 — {@code MinecraftClient.tick()}, HEAD.
 * Même nom Yarn qu'en 1.21.11 ; aucun mod tiers ne précharge cette classe sur
 * cette version (conception 1.8.9, D1), le risque de retransform tardif noté en
 * 1.21.11 ne s'applique pas.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ClientTickMixin189 {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void la$dispatchClientTick(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CLIENT_TICK, null);
    }
}
