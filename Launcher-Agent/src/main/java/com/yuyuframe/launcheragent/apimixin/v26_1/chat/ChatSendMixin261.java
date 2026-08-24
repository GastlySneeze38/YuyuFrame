package com.yuyuframe.launcheragent.apimixin.v26_1.chat;

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
 * ANNULABLE (contrairement à {@link ChatReceiveMixin261}, notification pure) —
 * Phase 4.5 (ROADMAP-agent.md), système de commandes client : {@code
 * ClientCommandRegistry} s'enregistre sur ce HookPoint et retourne {@code
 * true} quand {@code content} correspond à une commande reconnue ("/yf ...",
 * "/shader-reload") — l'envoi vanilla est alors annulé (la commande ne part
 * JAMAIS au serveur), même convention "true = pris en charge" que les hooks
 * HUD (voir {@code VanillaHookRegistry#dispatch}).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ChatSendMixin261 {

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void la$dispatchChatSend(String content, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.CHAT_SEND, content)) {
            ci.cancel();
        }
    }
}
