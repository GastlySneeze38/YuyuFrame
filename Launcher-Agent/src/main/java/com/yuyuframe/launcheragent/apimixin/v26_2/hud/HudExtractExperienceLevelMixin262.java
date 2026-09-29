package com.yuyuframe.launcheragent.apimixin.v26_2.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapExperienceLevel} vers {@link HookPoint#HUD_EXTRACT_EXPERIENCE_LEVEL}
 * — voir {@link HudExtractCameraOverlayMixin262} pour l'explication du
 * pattern. {@code ContextualBarRenderer.extractExperienceLevel} est
 * STATIQUE — pas de paramètre {@code instance} en tête, comme {@link
 * HudExtractArmorMixin262}. {@code DeltaTracker} capturé en {@code @Local}
 * côté Fabric d'origine, omis ici (même choix qu'ailleurs dans ce dossier).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractExperienceLevelMixin262 {

    @WrapOperation(method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private void la$dispatchExperienceLevel(GuiGraphicsExtractor graphics, Font font, int level, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL, graphics)) {
            renderVanilla.call(graphics, font, level);
        }
    }
}
