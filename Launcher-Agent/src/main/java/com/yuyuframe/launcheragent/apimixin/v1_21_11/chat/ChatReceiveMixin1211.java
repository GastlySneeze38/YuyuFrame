package com.yuyuframe.launcheragent.apimixin.v1_21_11.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CHAT_RECEIVE} sur 1.21.11 — pendant de {@code ChatReceiveMixin261}.
 *
 * <pre>
 *   26.1.2 (Mojang)                              1.21.11 (Yarn)
 *   ChatListener                                 MessageHandler
 *     handleSystemMessage(Component, boolean)      onGameMessage(Text, boolean)
 *     handlePlayerChatMessage(PlayerChatMessage,   onChatMessage(SignedMessage,
 *        GameProfile, ChatType$Bound)                GameProfile, MessageType$Parameters)
 * </pre>
 *
 * Mêmes deux points, même moment (TAIL), même {@code ctx} : le message pour
 * le premier, {@code null} pour le second. Le message est capturé en
 * {@link Coerce} {@code Object} — son type réel est obfusqué. Aucun handler ne
 * le lit aujourd'hui ({@code ChatEnhancementsModule} ne s'en sert que comme
 * signal).
 */
@Mixin(targets = "net.minecraft.client.network.message.MessageHandler")
public abstract class ChatReceiveMixin1211 {

    @Inject(method = "onGameMessage(Lnet/minecraft/text/Text;Z)V", at = @At("TAIL"), require = 0)
    private void la$dispatchGameMessage(@Coerce Object message, boolean overlay, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, message);
    }

    @Inject(method = "onChatMessage(Lnet/minecraft/network/message/SignedMessage;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/message/MessageType$Parameters;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchChatMessage(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CHAT_RECEIVE, null);
    }
}
