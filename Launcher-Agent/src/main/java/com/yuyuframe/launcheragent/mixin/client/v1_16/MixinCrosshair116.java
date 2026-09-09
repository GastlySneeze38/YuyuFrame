package com.yuyuframe.launcheragent.mixin.client.v1_16;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent 1.13-1.16.x de {@code MixinCrosshair189} — voir historique de
 * session (audit modules) : "crosshair personnalisé marche mais ne cache pas
 * celui vanilla" sur ce bracket, car l'ancien Mixin ne cible que 1.8.9.
 *
 * DIFFÉRENCE avec 1.8.9 : pas de méthode booléenne {@code showCrosshair()} à
 * annuler (le dessin vanilla du crosshair en 1.8.9 est gaté par un {@code if}
 * interne à une grosse méthode HUD monolithique). En 1.16.5, {@code
 * InGameHud.renderCrosshair(MatrixStack)} est une méthode DÉDIÉE et
 * directement appelée (confirmé dans les mappings : {@code (Ldfm;)V d
 * method_1736 renderCrosshair}, sous {@code net.minecraft.client.gui.hud.InGameHud})
 * — donc on annule directement TOUT son corps via {@code @Inject(cancellable)}
 * au lieu de forcer le retour d'un booléen intermédiaire.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class MixinCrosshair116 {

    @Inject(method = "renderCrosshair(Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinCrosshair116] la$hideVanillaCrosshair: " + t);
        }
    }
}
