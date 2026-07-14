package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Motion blur" (nom utilisateur/joueur) = le flou/déformation d'écran de
 * l'effet Nausées — nom interne réel Mojang "Confusion", méthode {@code
 * Gui.extractConfusionOverlay(GuiGraphicsExtractor, float)} (vérifié par
 * javap + désassemblage de {@code extractCameraOverlays}, jar client
 * 26.1.2 réel). Même motif que {@code CrosshairMixin261} (annulation pure,
 * aucun paramètre à lire, pas besoin de stub {@code GuiGraphicsExtractor}
 * ici contrairement à {@code ClearOverlaysMixin261} qui doit lire
 * l'{@code Identifier}).
 *
 * NON RETESTÉ EN JEU au moment de l'écriture.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class NoConfusionOverlayMixin261 {

    @Inject(method = "extractConfusionOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideConfusionOverlay(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("no-motion-blur");
            if (module != null && module.isEnabled()) ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[NoConfusionOverlayMixin261] la$hideConfusionOverlay: " + t);
        }
    }
}
