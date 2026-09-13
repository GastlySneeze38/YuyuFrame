package com.yuyuframe.launcheragent.apimixin.v1_8_9.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CLIENT_LEVEL_LOAD} sur 1.8.9 —
 * {@code MinecraftClient.connect(ClientWorld, String)}, TAIL. La surcharge à un
 * paramètre y délègue ; un monde {@code null} (déconnexion) n'est pas un chargement.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ClientLevelLoadMixin189 {

    @Inject(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchClientLevelLoad(@Coerce Object world, String loadingMessage, CallbackInfo ci) {
        if (world != null) {
            VanillaHookRegistry.dispatch(HookPoint.CLIENT_LEVEL_LOAD, world);
        }
    }
}
