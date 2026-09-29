package com.yuyuframe.launcheragent.apimixin.v26_1_1.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Réconciliation avec l'ancien {@code mixin.client.v26_1.ChatListenerMixin261}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — notamment pourquoi TAIL, pas
 * HEAD : {@code handleSystemMessage}/{@code handlePlayerChatMessage} ajoutent
 * eux-mêmes le message à {@code ChatComponent} de façon synchrone DANS leur
 * propre corps — en TAIL seulement le message est déjà présent en tête de
 * {@code ChatComponent.allMessages} quand un handler (ex: {@code
 * ChatEnhancementsModule}) va le relire). Porte aussi (simplifié) {@code
 * ChatListenerMixin#fabric_allowSystemMessage} (fabric-message-api-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#CHAT_RECEIVE}.
 *
 * Les DEUX méthodes dispatchent le MÊME HookPoint : {@code
 * handleSystemMessage} couvre la plupart des serveurs modifiés (ex:
 * Hypixel — tout son chat, y compris les messages du joueur, arrive tagué
 * "[System] [CHAT]"), {@code handlePlayerChatMessage} couvre le chat signé
 * vanilla standard. {@code ctx = Component} pour le premier (déjà stubé) ;
 * {@code null} pour le second ({@code PlayerChatMessage} pas encore stubé —
 * pas nécessaire tant qu'aucun handler n'a besoin du contenu à CE point
 * précis, voir {@code ChatEnhancementsModule} qui relit l'état du chat
 * lui-même plutôt que de dépendre de ce paramètre).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.chat.ChatListener")
abstract class ChatReceiveMixin2611 {

    @Inject(method = "handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("TAIL"), require = 0)
    private void la$dispatchSystemMessage(Component message, boolean overlay, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, message);
    }

    @Inject(method = "handlePlayerChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchPlayerChatMessage(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, null);
    }
}
