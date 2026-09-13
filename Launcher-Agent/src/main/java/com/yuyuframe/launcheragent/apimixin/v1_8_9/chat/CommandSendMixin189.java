package com.yuyuframe.launcheragent.apimixin.v1_8_9.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#COMMAND_SEND} sur 1.8.9 — textes en « / » de
 * {@code ClientPlayerEntity.sendChatMessage(String)}, contexte SANS le « / »
 * comme le prévoit le HookPoint. Voir {@code ChatSendMixin189} pour le partage
 * de la méthode avec les messages.
 */
@Mixin(targets = "net.minecraft.entity.player.ClientPlayerEntity")
public abstract class CommandSendMixin189 {

    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchCommandSend(String content, CallbackInfo ci) {
        if (content == null || !content.startsWith("/")) return;
        if (VanillaHookRegistry.dispatch(HookPoint.COMMAND_SEND, content.substring(1))) {
            ci.cancel();
        }
    }
}
