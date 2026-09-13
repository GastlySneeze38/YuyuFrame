package com.yuyuframe.launcheragent.apimixin.v1_21_11.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Envoi d'une commande → {@link HookPoint#COMMAND_SEND} — pendant 1.21.11 de
 * {@code CommandSendMixin261}.
 *
 * <p>Cible {@code ClientPlayNetworkHandler.sendChatCommand(String)}
 * ({@code method_45730}, Yarn 1.21.11), appelée par {@code ChatScreen}
 * ({@code class_408}) pour tout texte commençant par « / », « / » retiré —
 * relu dans le bytecode intermediary. Sélecteur traduit par la refmap
 * ({@code LauncherMixinService}), sans quoi l'injection échouerait en silence
 * sur ce bracket obfusqué.
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayNetworkHandler")
public abstract class CommandSendMixin1211 {

    @Inject(method = "sendChatCommand(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchCommandSend(String command, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.COMMAND_SEND, command)) {
            ci.cancel();
        }
    }
}
