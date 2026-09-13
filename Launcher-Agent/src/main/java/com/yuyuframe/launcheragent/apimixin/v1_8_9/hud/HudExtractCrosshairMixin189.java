package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#HUD_EXTRACT_CROSSHAIR} sur 1.8.9 — {@code InGameHud.showCrosshair()Z}.
 *
 * <p>La 1.8.9 n'a pas de méthode qui dessine la croix : {@code InGameHud.render(F)}
 * teste {@code showCrosshair()} puis dessine en ligne (javap {@code avo.a(F)V},
 * seul appelant). Répondre {@code false} masque donc exactement la croix vanilla —
 * même point que l'ancien {@code MixinCrosshair189}. {@code ctx} = {@code null} :
 * aucun contexte de dessin sur cette version.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractCrosshairMixin189 {

    @Inject(method = "showCrosshair()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchCrosshair(CallbackInfoReturnable<Boolean> cir) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CROSSHAIR, null)) {
            cir.setReturnValue(false);
        }
    }
}
