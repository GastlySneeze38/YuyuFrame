package com.yuyuframe.launcheragent.apimixin.v1_8_9.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CHAT_SEND} sur 1.8.9 — {@code ClientPlayerEntity.sendChatMessage(String)}.
 *
 * <p>En 1.8.9, messages ET commandes partent par cette même méthode (pas de
 * {@code sendChatCommand} séparée comme depuis la 1.19) : ce mixin ne garde
 * que les messages, {@code CommandSendMixin189} les textes en « / ».
 */
@Mixin(targets = "net.minecraft.entity.player.ClientPlayerEntity")
public abstract class ChatSendMixin189 {

    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchChatSend(String content, CallbackInfo ci) {
        if (content == null || content.startsWith("/")) return;
        if (VanillaHookRegistry.dispatch(HookPoint.CHAT_SEND, content)) {
            ci.cancel();
        }
    }
}
