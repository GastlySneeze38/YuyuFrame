package com.yuyuframe.launcheragent.apimixin.v26_3.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapMiscOverlays} (fabric-rendering-v1, voir
 * mixinapi/26.1.2/fabric-rendering-v1/client/.../GuiMixin.java) vers
 * {@link HookPoint#HUD_EXTRACT_CAMERA_OVERLAY} — un mixin par HookPoint (voir
 * la javadoc de {@link HookPoint}), pas un seul gros mixin regroupant les 22
 * points d'accroche HUD comme Fabric le fait.
 *
 * Simplification volontaire par rapport à l'original : Fabric route ce hook
 * à travers son propre système de couches ({@code HudElementRegistryImpl},
 * before/after/instead par élément COMPOSABLES) — non reproduit à ce niveau
 * de détail. {@link VanillaHookRegistry#dispatch} tourne AVANT le dessin
 * vanilla ; si {@link VanillaHookRegistry.HookHandler#handle} d'AU MOINS UN
 * module enregistré retourne {@code true} (voir sa javadoc), le dessin
 * vanilla est SAUTÉ — un module peut donc remplacer entièrement cet élément
 * HUD. Tous les handlers enregistrés sont appelés dans tous les cas (pas de
 * court-circuit), donc un module purement observateur reçoit toujours sa
 * notification même si un autre a pris la main sur le dessin.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
abstract class HudExtractCameraOverlayMixin263 {

    @Inject(method = "extractCameraOverlays(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchCameraOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CAMERA_OVERLAY, graphics)) {
            ci.cancel();
        }
    }
}
