package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Écran-titre atteint sur 1.8.9 — pendant de {@code TitleScreenMixin1211},
 * portage apimixin de {@code mixin.client.v1_8.TitleScreenMixin189} (conservé
 * comme référence).
 *
 * <p>{@code init()V} n'est pas redéclarée par {@code TitleScreen} dans Yarn
 * legacy : l'entrée de refmap replie sur {@code Screen} (officiel {@code axu.b()V}).
 * Le signal au launcher passe par {@link AgentBridge}, jamais par {@code runtime/}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.TitleScreen")
public abstract class TitleScreenMixin189 {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        LauncherLog.ui(3, "[LauncherAgent] Hook TitleScreen.init() OK (1.8.9) — pipeline Mixin opérationnel");
        // Signal lu par le launcher Rust (stdout du process, voir orchestrator.rs).
        LauncherLog.info("[YUYUFRAME_READY]");
        try {
            AgentBridge.get(this.getClass().getClassLoader())
                .signalReady(System.getProperty("launcheragent.readyEvent"));
        } catch (Throwable t) {
            LauncherLog.warn("[LauncherAgent] TitleScreenMixin189: signal de démarrage échoué : " + t);
        }
    }
}
