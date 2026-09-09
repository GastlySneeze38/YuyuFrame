package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ipc.ReadyEventSignal;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.TitleScreenMixin261} (voir
 * ce fichier pour l'historique) — logique IDENTIQUE, package apimixin
 * uniquement. Signal ponctuel de démarrage, pas de dispatch {@code
 * VanillaHookRegistry} (rien n'a besoin de l'observer génériquement).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.TitleScreen")
public abstract class TitleScreenMixin261 {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        LauncherLog.info("[YUYUFRAME_READY]");
        try {
            ReadyEventSignal.signalOnce(System.getProperty("launcheragent.readyEvent"));
        } catch (Throwable t) {
            LauncherLog.warn("[LauncherAgent] TitleScreenMixin261 (apimixin): ReadyEventSignal.signalOnce a échoué au point d'appel : " + t);
        }
    }
}
