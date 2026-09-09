package com.yuyuframe.launcheragent.mixin.client.v1_20_4;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent 1.17-1.20.4 de {@code GlobalTickMixin116} — même raison d'être
 * (ouverture/fermeture réelle de l'écran custom sur {@code
 * MinecraftClient.tick()}, jamais depuis le TAIL de render(), voir l'ainé
 * 1.16.5 pour le détail complet du bug de réentrance GLFW que ça évite).
 * {@code tick()V} est une signature stable, inchangée entre 1.16.5 et 1.20.4
 * (vérifié dans mappings/yarn-1.20.4-mergedv2.jar : method_1574 tick, sous
 * {@code evi}/MinecraftClient).
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class GlobalTickMixin1204 {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void la$onTickHead(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = this;
            Object currentScreen = ScreenBridge1204.getCurrentScreen(mc);

            if (currentScreen == null) {
                if (ScreenBridge1204.consumePendingMenuOpen()) {
                    LauncherLog.info("[LauncherAgent] DIAG-1204: ouverture UiMainMenuScreen (tick)");
                    UiMainMenuScreen screen = new UiMainMenuScreen(null);
                    ScreenBridge1204.setScreen(mc, screen);
                }
                return;
            }

            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) ScreenBridge1204.setScreen(mc, target);
                    else ScreenBridge1204.closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalTickMixin1204: " + t);
        }
    }
}
