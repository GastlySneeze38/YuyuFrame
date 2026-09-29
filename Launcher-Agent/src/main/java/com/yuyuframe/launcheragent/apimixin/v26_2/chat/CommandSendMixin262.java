package com.yuyuframe.launcheragent.apimixin.v26_2.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Envoi d'une commande → {@link HookPoint#COMMAND_SEND}.
 *
 * <p>Cible {@code ClientPacketListener.sendCommand(String)} : c'est là que
 * {@code ChatScreen.handleChatInput} envoie tout texte commençant par « / »,
 * « / » retiré (bytecode 26.1.2 : {@code startsWith("/")} →
 * {@code sendCommand(substring(1))}). Les macros passent par la même méthode
 * ({@code NetworkData.sendCommand}), une macro {@code /yf} est donc elle aussi
 * exécutée côté client.
 *
 * <p>Annulable : un handler qui répond {@code true} a pris la commande en
 * charge, elle ne part pas au serveur.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class CommandSendMixin262 {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void la$dispatchCommandSend(String command, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.COMMAND_SEND, command)) {
            ci.cancel();
        }
    }
}
