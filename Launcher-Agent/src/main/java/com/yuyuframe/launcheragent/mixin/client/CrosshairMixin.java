package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Masque le crosshair vanilla sur le bracket 1.21.11 quand le module
 * "custom-crosshair" (CrosshairModule) est actif — même pattern que
 * MixinCrosshair1214 (bracket 1.21.4), même signature vérifiée directement
 * dans mappings/yarn-1.21.11-mergedv2.jar (cache local) :
 * {@code (Lgir;Lgez;)V k method_1736 renderCrosshair} — DrawContext +
 * RenderTickCounter, structurellement identique à 1.21.4 (même
 * method_1736), seules les lettres officielles diffèrent (gir/gez vs
 * fof/fla) — sans incidence, la résolution passe par le nom Yarn named, pas
 * les lettres officielles brutes.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class CrosshairMixin {

    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[CrosshairMixin] la$hideVanillaCrosshair: " + t);
        }
    }
}
