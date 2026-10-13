package com.yuyuframe.launcheragent.apimixin.v26_1_2.lifecycle;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SERVER_CONNECT} — {@code ClientPacketListener.handleLogin},
 * à la sortie : le joueur vient d'entrer dans un monde (connexion à un
 * serveur, ou ouverture d'un monde solo). L'entrée existait dans l'enum sans
 * aucun mixin derrière ; c'est le premier.
 *
 * <p>Le paquet n'est pas capturé : une notification suffit, et cela évite un
 * stub (même choix que {@code PiercingAttackMixin261}).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ServerConnectMixin261 {

    @Inject(method = "handleLogin", at = @At("TAIL"), require = 0)
    private void la$dispatchServerConnect(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SERVER_CONNECT, null);
    }
}
