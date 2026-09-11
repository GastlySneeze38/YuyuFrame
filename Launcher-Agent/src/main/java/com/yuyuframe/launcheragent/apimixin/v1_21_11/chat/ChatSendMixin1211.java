package com.yuyuframe.launcheragent.apimixin.v1_21_11.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CHAT_SEND} sur 1.21.11 — pendant de {@code ChatSendMixin261}.
 *
 * <pre>
 *   26.1.2 : ClientPacketListener.sendChat(String)
 *   1.21.11 : ClientPlayNetworkHandler.sendChatMessage(String)
 * </pre>
 *
 * {@code ctx} = le texte envoyé, une {@code String} — type Java, identique sur
 * toutes les versions, donc capturable tel quel. C'est ce qu'attendent
 * {@code ClientCommandRegistry} (interception des commandes client, d'où
 * {@code cancellable}) et {@code ChatEnhancementsModule}.
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayNetworkHandler")
public abstract class ChatSendMixin1211 {

    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchChatSend(String content, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.CHAT_SEND, content)) {
            ci.cancel();
        }
    }
}
