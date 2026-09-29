package com.yuyuframe.launcheragent.apimixin.v26_2.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arbre de commandes reçu → {@link HookPoint#COMMAND_TREE_RECEIVE}, contexte :
 * la connexion.
 *
 * <p>Cible {@code ClientPacketListener.handleCommands} en {@code TAIL}. Relu
 * dans le bytecode 26.1.2 : la méthode commence par
 * {@code PacketUtils.ensureRunningOnSameThread} (qui interrompt l'appel sur le
 * thread réseau et le rejoue sur le thread principal), puis construit un
 * NOUVEAU {@code CommandDispatcher} et l'affecte au champ {@code commands}
 * juste avant le {@code return}. En {@code TAIL}, on est donc sur le thread
 * principal et l'arbre est celui qui vient d'arriver.
 *
 * <p>Handler sans paramètre de paquet : on n'en a pas besoin, et un paramètre
 * {@code @Inject} typé en stub est un piège connu — voir
 * {@code docs/LauncherAgent/module-bracket-audit.md}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class CommandTreeMixin262 {

    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void la$onCommandTreeReceived(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.COMMAND_TREE_RECEIVE, this);
    }
}
