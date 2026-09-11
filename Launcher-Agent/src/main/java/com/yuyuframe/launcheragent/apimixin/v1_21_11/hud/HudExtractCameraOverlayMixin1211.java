package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_CAMERA_OVERLAY} sur 1.21.11 —
 * {@code InGameHud.renderMiscOverlays} (Mojang {@code renderCameraOverlays},
 * {@code extractCameraOverlays} en 26.1.2).
 *
 * <p>Forme commune à tout le HUD 1.21.11, voir {@link HudExtractCrosshairMixin1211} :
 * HEAD + {@code cancellable} sur la méthode appelée, paramètres en
 * {@link Coerce} {@code Object}, contexte de dessin transmis. Correspondance
 * Mojang ↔ Yarn vérifiée par nom officiel ({@code giq.d(gir,gez)}).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractCameraOverlayMixin1211 {

    @Inject(method = "renderMiscOverlays(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchCameraOverlay(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CAMERA_OVERLAY, context)) {
            ci.cancel();
        }
    }
}
