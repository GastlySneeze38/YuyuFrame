package com.yuyuframe.launcheragent.apimixin.v26_2.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code ClientPacketListenerMixin#fabric_allowSendChatMessage}
 * (fabric-message-api-v1, voir mixinapi/26.1.2) vers {@link HookPoint#CHAT_SEND}.
 *
 * ANNULABLE (contrairement à {@link ChatReceiveMixin262}, notification pure) :
 * un handler qui répond {@code true} empêche le message de partir, même
 * convention "true = pris en charge" que les hooks HUD (voir
 * {@code VanillaHookRegistry#dispatch}).
 *
 * <p>MESSAGES uniquement : un texte commençant par « / » n'arrive jamais ici,
 * {@code ChatScreen} l'envoie par {@code sendCommand} — voir
 * {@link CommandSendMixin262}, où {@code ClientCommandRegistry} intercepte
 * désormais les commandes {@code /yf} (il était abonné ici jusqu'au
 * 2026-09-13, et ne voyait donc aucune commande).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ChatSendMixin262 {

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void la$dispatchChatSend(String content, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.CHAT_SEND, content)) {
            ci.cancel();
        }
    }
}
