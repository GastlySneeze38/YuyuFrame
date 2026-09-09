package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent ~1.21-1.21.5 de {@code MixinCrosshair1204} — DIFFÉRENCE RÉELLE
 * (pas juste un renommage de package) : {@code InGameHud.renderCrosshair}
 * prend maintenant DEUX paramètres, {@code DrawContext} ET {@code
 * RenderTickCounter} (vérifié dans mappings/yarn-1.21.4-mergedv2.jar :
 * {@code (Lfof;Lfla;)V j method_1736 renderCrosshair}, {@code fla} =
 * RenderTickCounter) — un paramètre de plus par rapport à 1.20.4 (juste
 * {@code DrawContext}), cohérent avec l'introduction de RenderTickCounter
 * dans toute la pile de rendu à partir de cette ère.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class MixinCrosshair1214 {

    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hideVanillaCrosshair(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("custom-crosshair");
            if (module != null && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinCrosshair1214] la$hideVanillaCrosshair: " + t);
        }
    }
}
