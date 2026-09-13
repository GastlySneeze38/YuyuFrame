package com.yuyuframe.launcheragent.apimixin.v1_8_9.chat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#CHAT_RECEIVE} sur 1.8.9 — message reçu du serveur.
 *
 * <p>{@code ClientPlayNetworkHandler.onChatMessage(ChatMessageS2CPacket)} passe
 * le texte du paquet à {@code ChatHud.addMessage(Text)} (ou au message
 * au-dessus de la barre pour le type 2), javap {@code bcy.a(fy)V}. On enveloppe
 * CET appel plutôt que {@code ChatHud.addMessage} lui-même, où arrivent aussi
 * les messages écrits localement (y compris par l'agent) : seul le site d'appel
 * dit « reçu du serveur ». {@code ctx} = le {@code Text}, comme en 1.21.11.
 *
 * <p>Déclarée par {@code ClientPlayPacketListener} dans Yarn legacy : l'entrée
 * de refmap replie sur cette interface.
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayNetworkHandler")
public abstract class ChatReceiveMixin189 {

    @WrapOperation(method = "onChatMessage(Lnet/minecraft/network/packet/s2c/play/ChatMessageS2CPacket;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;addMessage(Lnet/minecraft/text/Text;)V"),
        require = 0)
    private void la$dispatchChatReceive(Object chatHud, Object message, Operation<Void> original) {
        original.call(chatHud, message);
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, message);
    }
}
