package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage apimixin de {@code mixin.client.TitleScreenMixin} (1.21.11, supprimé
 * le 2026-09-16, historique dans git) — même logique que {@code TitleScreenMixin261}.
 *
 * <p>Seule différence avec l'original : le signal au launcher passe par
 * {@link AgentBridge} au lieu d'importer {@code runtime.ipc.ReadyEventSignal}
 * — {@code apimixin/} ne nomme plus {@code runtime/}, voir la javadoc
 * d'{@code AgentBridge}.
 *
 * <p>Cible en nom Yarn ({@code net.minecraft.client.gui.screen.TitleScreen}) :
 * version obfusquée, traduite par le remapper pour la classe et par le refmap
 * pour la méthode — {@code init()} étant héritée de {@code Screen}, son entrée
 * de refmap a un repli sur cette classe (voir {@code LauncherMixinService}).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.TitleScreen")
public abstract class TitleScreenMixin1211 {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        LauncherLog.ui(3, "[LauncherAgent] Hook TitleScreen.init() OK (1.21.11) — pipeline Mixin opérationnel");
        // Signal lu par le launcher Rust (stdout du process, voir orchestrator.rs).
        LauncherLog.info("[YUYUFRAME_READY]");
        try {
            AgentBridge.get(this.getClass().getClassLoader())
                .signalReady(System.getProperty("launcheragent.readyEvent"));
        } catch (Throwable t) {
            LauncherLog.warn("[LauncherAgent] TitleScreenMixin1211: signal de démarrage échoué : " + t);
        }
    }
}
