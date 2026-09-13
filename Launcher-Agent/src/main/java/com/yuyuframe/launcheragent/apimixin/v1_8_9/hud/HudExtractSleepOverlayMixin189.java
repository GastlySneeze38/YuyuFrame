package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#HUD_EXTRACT_SLEEP_OVERLAY} sur 1.8.9 — le voile du sommeil,
 * dessiné en ligne dans {@code InGameHud.render(float)} par un {@code fill}
 * statique : c'est le SEUL {@code fill} de la méthode (javap {@code avo.a(F)V},
 * section de profiler {@code "sleep"}), aucune tranche nécessaire.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractSleepOverlayMixin189 {

    @WrapWithCondition(method = "render(F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;fill(IIIII)V"),
        require = 0)
    private static boolean la$dispatchSleepOverlay(int x1, int y1, int x2, int y2, int color) {
        return !VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SLEEP_OVERLAY, null);
    }
}
