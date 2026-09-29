package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.TitleScreenMixin2610} (supprimé
 * le 2026-09-16 ; voir l'historique git) — logique IDENTIQUE, package apimixin
 * uniquement. Signal ponctuel de démarrage, pas de dispatch {@code
 * VanillaHookRegistry} (rien n'a besoin de l'observer génériquement).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.TitleScreen")
public abstract class TitleScreenMixin2610 {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        LauncherLog.info("[YUYUFRAME_READY]");
        try {
            AgentBridge.get(this.getClass().getClassLoader())
                .signalReady(System.getProperty("launcheragent.readyEvent"));
        } catch (Throwable t) {
            LauncherLog.warn("[LauncherAgent] TitleScreenMixin2610 (apimixin): ReadyEventSignal.signalOnce a échoué au point d'appel : " + t);
        }
    }
}
