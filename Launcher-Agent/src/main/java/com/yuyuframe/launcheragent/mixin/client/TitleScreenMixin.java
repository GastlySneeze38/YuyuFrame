package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ipc.ReadyEventSignal;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin de fumée — confirme que le pipeline Mixin standalone de LauncherAgent
 * s'initialise correctement (mappings Yarn + transformer + retransform),
 * indépendamment du p2p-agent. Ne touche à rien d'autre.
 *
 * À remplacer par le mixin réel sur l'écran resource packs une fois
 * l'écran custom et content_core.dll posés (voir docs/LauncherAgent/index.md).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.TitleScreen")
public abstract class TitleScreenMixin {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        LauncherLog.ui(3, "[LauncherAgent] Hook TitleScreen.init() OK — pipeline Mixin opérationnel");
        // Signal lu par le launcher Rust (stdout du process, voir orchestrator.rs)
        // pour savoir que Minecraft a fini son propre chargement interne (splash
        // + ressources) et affiche enfin le menu principal — comble le "trou"
        // entre la fin de nos téléchargements et le jeu réellement visible.
        LauncherLog.info("[YUYUFRAME_READY]");
        try {
            ReadyEventSignal.signalOnce(System.getProperty("launcheragent.readyEvent"));
        } catch (Throwable t) {
            LauncherLog.warn("[LauncherAgent] ReadyEventSignal.signalOnce a échoué au point d'appel : " + t);
        }
    }
}
