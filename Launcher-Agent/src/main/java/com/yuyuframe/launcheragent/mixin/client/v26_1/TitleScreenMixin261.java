package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ipc.ReadyEventSignal;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code TitleScreenMixin}) pour MC 26.1+ —
 * seul le PACKAGE change : {@code net.minecraft.client.gui.screen.TitleScreen}
 * → {@code net.minecraft.client.gui.screens.TitleScreen} ("screen"→"screens",
 * pluriel, confirmé dans le jar réel). {@code init()V} lui-même est
 * INCHANGÉ : {@code Screen} a désormais un {@code init(int,int)} public en
 * plus (fixe width/height puis appelle CE MÊME {@code init()V} sans
 * argument, confirmé par désassemblage bytecode du jar client 26.1.2 réel —
 * {@code invokevirtual Method init:()V}), donc ce hook continue de se
 * déclencher exactement au même moment logique qu'avant (premier affichage).
 *
 * Vérifié en jeu (26.1.2).
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
            LauncherLog.warn("[LauncherAgent] ReadyEventSignal.signalOnce a échoué au point d'appel : " + t);
        }
    }
}
