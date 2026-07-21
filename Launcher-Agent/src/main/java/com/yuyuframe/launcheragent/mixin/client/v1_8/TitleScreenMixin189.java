package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.ipc.ReadyEventSignal;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smoke test 1.8.9 — confirme que le pipeline Mixin multi-version fonctionne.
 *
 * Cible : net.minecraft.client.gui.screen.TitleScreen (nom Legacy Fabric Yarn 1.8.9+build.604
 * — Legacy Fabric utilise la nomenclature Yarn MODERNE même sur 1.8.9, PAS les noms MCP
 * historiques type "GuiMainMenu" ; vérifié dans mappings/mappings-1.8.9.tiny : class_624).
 * Méthode : "init" — héritée de Screen (class_388, jamais surchargée directement sur
 * TitleScreen dans ces mappings, official "b" désc "()V" sur Screen — voir method_1044).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.TitleScreen")
public abstract class TitleScreenMixin189 {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void la$onInit(CallbackInfo ci) {
        LauncherLog.ui(3, "[LauncherAgent] Hook TitleScreen.init() OK — pipeline 1.8.9 opérationnel");
        LauncherLog.info("[YUYUFRAME_READY]");
        ReadyEventSignal.signalOnce(System.getProperty("launcheragent.readyEvent"));
    }
}
