package com.yuyuframe.launcheragent.apimixin.v1_21_11.chat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arbre de commandes reçu → {@link HookPoint#COMMAND_TREE_RECEIVE} — pendant
 * 1.21.11 de {@code CommandTreeMixin261}.
 *
 * <p>Cible {@code ClientPlayNetworkHandler.onCommandTree(CommandTreeS2CPacket)}
 * ({@code method_11145}, Yarn 1.21.11) en {@code TAIL}. Même structure relue
 * dans le jar intermediary : {@code ensureRunningOnSameThread}, puis
 * {@code new CommandDispatcher(root)} affecté à {@code field_3696} juste avant
 * le {@code return}. Sélecteur traduit par la refmap
 * ({@code LauncherMixinService}).
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayNetworkHandler")
public abstract class CommandTreeMixin1211 {

    @Inject(method = "onCommandTree(Lnet/minecraft/network/packet/s2c/play/CommandTreeS2CPacket;)V", at = @At("TAIL"))
    private void la$onCommandTreeReceived(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.COMMAND_TREE_RECEIVE, this);
    }
}
