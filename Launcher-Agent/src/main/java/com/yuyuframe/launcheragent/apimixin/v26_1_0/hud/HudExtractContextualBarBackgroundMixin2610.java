package com.yuyuframe.launcheragent.apimixin.v26_1_0.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapExtractInfoBar} vers {@link HookPoint#HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND}
 * — voir {@link HudExtractCameraOverlayMixin2610} pour l'explication du
 * pattern. Cible toujours {@code Gui} (pas {@code ContextualBarRenderer}) :
 * {@code method="extractHotbarAndDecorations"} est une méthode de {@code Gui},
 * {@code ContextualBarRenderer} n'est que le type de l'appel wrappé.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractContextualBarBackgroundMixin2610 {

    @WrapOperation(method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void la$dispatchContextualBarBackground(ContextualBarRenderer instance, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND, graphics)) {
            renderVanilla.call(instance, graphics, deltaTracker);
        }
    }
}
