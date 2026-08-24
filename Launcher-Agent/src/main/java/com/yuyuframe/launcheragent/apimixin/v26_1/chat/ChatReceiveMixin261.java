package com.yuyuframe.launcheragent.apimixin.v26_1.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code ChatListenerMixin#fabric_allowSystemMessage}
 * (fabric-message-api-v1, voir mixinapi/26.1.2) vers {@link HookPoint#CHAT_RECEIVE}
 * — l'original a 5 points d'injection séparés (signé, signé filtré, sans
 * profil, système, overlay) avec ALLOW/MODIFY/CANCEL complets via
 * {@code @Local LocalRef} pour réécrire le message. Ici, un seul point
 * ({@code handleSystemMessage} HEAD) en notification simple — ni annulation
 * ni modification du contenu pour l'instant. Existe déjà une variante maison
 * ({@code ChatListenerMixin261} dans {@code mixin/client/v26_1/} probablement
 * — à réconcilier avant d'activer ce mixin dans un JSON Mixin.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.chat.ChatListener")
abstract class ChatReceiveMixin261 {

    @Inject(method = "handleSystemMessage", at = @At("HEAD"))
    private void la$dispatchChatReceive(Component message, boolean remote, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, message);
    }
}
