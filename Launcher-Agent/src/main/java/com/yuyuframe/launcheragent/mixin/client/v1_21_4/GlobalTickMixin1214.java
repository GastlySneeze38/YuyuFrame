package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent ~1.21-1.21.5 de {@code GlobalTickMixin1204} — même raison d'être
 * (ouverture/fermeture réelle de l'écran custom sur {@code
 * MinecraftClient.tick()}, jamais depuis le TAIL de render()). {@code tick()V}
 * reste stable (vérifié dans mappings/yarn-1.21.4-mergedv2.jar : method_1574
 * tick, sous {@code flk}/MinecraftClient).
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class GlobalTickMixin1214 {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void la$onTickHead(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = this;
            Object currentScreen = ScreenBridge1214.getCurrentScreen(mc);

            if (currentScreen == null) {
                if (ScreenBridge1214.consumePendingMenuOpen()) {
                    LauncherLog.info("[LauncherAgent] DIAG-1214: ouverture UiMainMenuScreen (tick)");
                    UiMainMenuScreen screen = new UiMainMenuScreen(null);
                    ScreenBridge1214.setScreen(mc, screen);
                }
                return;
            }

            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) ScreenBridge1214.setScreen(mc, target);
                    else ScreenBridge1214.closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalTickMixin1214: " + t);
        }
    }
}
