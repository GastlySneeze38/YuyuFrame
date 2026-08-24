package com.yuyuframe.launcheragent.apimixin.v26_1.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code ClientPacketListenerMixin#fabric_allowSendChatMessage}
 * (fabric-message-api-v1, voir mixinapi/26.1.2) vers {@link HookPoint#CHAT_SEND}
 * — voir {@link ChatReceiveMixin261} pour la même simplification (notification
 * seule, ni annulation ni réécriture du contenu envoyé).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ChatSendMixin261 {

    @Inject(method = "sendChat", at = @At("HEAD"))
    private void la$dispatchChatSend(String content, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_SEND, content);
    }
}
