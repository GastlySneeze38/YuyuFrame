package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractCameraOverlayMixin261 {

    @WrapOperation(method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractCameraOverlays(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void la$dispatchCameraOverlay(Gui instance, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CAMERA_OVERLAY, graphics)) {
            renderVanilla.call(instance, graphics, deltaTracker);
        }
    }
}
