package com.yuyuframe.launcheragent.apimixin.v1_8_9.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CHAT_RECEIVE} sur 1.8.9 — message reçu du serveur.
 *
 * <p>{@code ClientPlayNetworkHandler.onChatMessage(ChatMessageS2CPacket)} passe
 * le texte du paquet à {@code ChatHud.addMessage(Text)} (ou au message
 * au-dessus de la barre pour le type 2), javap {@code bcy.a(fy)V}. On se place
 * JUSTE APRÈS cet appel plutôt que dans {@code ChatHud.addMessage} lui-même, où
 * arrivent aussi les messages écrits localement (y compris par l'agent) : seul
 * ce site d'appel dit « reçu du serveur ».
 *
 * <h2>{@code @Inject} et non {@code @WrapOperation} (2026-09-15)</h2>
 *
 * La première version enveloppait l'appel avec des arguments typés
 * {@code Object} : MixinExtras refuse, il exige les vrais types obfusqués
 * ({@code avt}, {@code eu}), et toute la classe restait non transformée.
 * {@code ctx} vaut donc {@code null}, comme pour le chat signé en 1.21.11 : son
 * seul consommateur ({@code ChatEnhancementsModule}) relit le chat lui-même.
 *
 * <p>Déclarée par {@code ClientPlayPacketListener} dans Yarn legacy : l'entrée
 * de refmap replie sur cette interface.
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayNetworkHandler")
public abstract class ChatReceiveMixin189 {

    @Inject(method = "onChatMessage(Lnet/minecraft/network/packet/s2c/play/ChatMessageS2CPacket;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;addMessage(Lnet/minecraft/text/Text;)V",
            shift = At.Shift.AFTER),
        require = 0)
    private void la$dispatchChatReceive(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, null);
    }
}
