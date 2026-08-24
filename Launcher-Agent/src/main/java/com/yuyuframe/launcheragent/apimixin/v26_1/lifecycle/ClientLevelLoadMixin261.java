package com.yuyuframe.launcheragent.apimixin.v26_1.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code MinecraftMixin#afterClientLevelChange} (fabric-lifecycle-events-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#CLIENT_LEVEL_LOAD} — TAIL de
 * {@code updateLevelInEngines}, seulement dispatché si {@code level != null}
 * (déconnexion = level null, pas un vrai "chargement").
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
abstract class ClientLevelLoadMixin261 {

    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("TAIL"))
    private void la$dispatchClientLevelLoad(ClientLevel level, boolean stopSound, CallbackInfo ci) {
        if (level != null) {
            VanillaHookRegistry.dispatch(HookPoint.CLIENT_LEVEL_LOAD, level);
        }
    }
}
