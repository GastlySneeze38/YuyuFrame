package com.yuyuframe.launcheragent.apimixin.v26_3.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SERVER_CONNECT} sur 26.3 — pendant de
 * {@code ServerConnectMixin261} : {@code ClientPacketListener.handleLogin}, à
 * la sortie. Le paquet n'est pas capturé.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ServerConnectMixin263 {

    @Inject(method = "handleLogin", at = @At("TAIL"), require = 0)
    private void la$dispatchServerConnect(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SERVER_CONNECT, null);
    }
}
