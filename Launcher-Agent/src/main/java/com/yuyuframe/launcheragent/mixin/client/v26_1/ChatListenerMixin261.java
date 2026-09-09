package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.gameplay.ChatEnhancementsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code ChatEnhancementsModule} lisait auparavant {@code messages.get(0)}
 * une fois par TICK client (~20/s) — race-y sur un salon très actif (voir sa
 * javadoc de tête : plusieurs messages peuvent arriver entre deux ticks, et
 * seul le plus récent est jamais lu, celui contenant une mention pouvant
 * être écrasé avant inspection). Vérifié en étudiant un mod réel équivalent
 * qui supporte déjà 26.1.2 (github.com/TerminalMC/ChatNotify, branche
 * mc26.1, fichier {@code mixin/ChatListenerMixin.java}) : il Mixin
 * directement {@code ChatListener}, pas la liste d'affichage — méthodes
 * appelées PAR LE JEU une fois par message REÇU, jamais manquées quel que
 * soit le débit.
 *
 * TAIL (pas HEAD) : {@code handleSystemMessage}/{@code handlePlayerChatMessage}
 * ajoutent eux-mêmes le message à {@code ChatComponent} de façon
 * synchrone dans leur propre corps (thread de rendu, pas de concurrence) —
 * en TAIL, le message est donc déjà présent en tête de
 * {@code ChatComponent.allMessages} quand {@code checkChatState()} (via
 * {@link ChatEnhancementsModule#onChatMessageObserved()}) va le relire par
 * réflexion, exactement comme avant, juste déclenché de façon fiable.
 *
 * {@code handleSystemMessage} couvre la plupart des serveurs modifiés (ex:
 * Hypixel — confirmé dans son propre log, TOUT son chat, y compris les
 * messages du joueur lui-même, arrive tagué "[System] [CHAT]", pas via le
 * chat signé vanilla) ; {@code handlePlayerChatMessage} couvre le chat
 * signé vanilla standard.
 *
 * NON RETESTÉ EN JEU au moment de l'écriture.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.chat.ChatListener")
public abstract class ChatListenerMixin261 {

    @Inject(method = "handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("TAIL"), require = 0)
    private void la$onSystemMessage(CallbackInfo ci) {
        try {
            ChatEnhancementsModule.onChatMessageObserved();
        } catch (Throwable t) {
            LauncherLog.err("[ChatListenerMixin261] la$onSystemMessage: " + t);
        }
    }

    @Inject(method = "handlePlayerChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("TAIL"), require = 0)
    private void la$onPlayerChatMessage(CallbackInfo ci) {
        try {
            ChatEnhancementsModule.onChatMessageObserved();
        } catch (Throwable t) {
            LauncherLog.err("[ChatListenerMixin261] la$onPlayerChatMessage: " + t);
        }
    }
}
