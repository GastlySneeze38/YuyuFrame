package com.yuyuframe.launcheragent.apimixin.v26_3.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ContextualBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapExtractInfoBar} vers {@link HookPoint#HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND}
 * — voir {@link HudExtractCameraOverlayMixin263} pour l'explication du
 * pattern. Cible {@code Hud} (pas {@code ContextualBar}) :
 * {@code method="extractHotbarAndDecorations"} est une méthode de {@code Hud},
 * {@code ContextualBar} n'est que le type de l'appel wrappé.
 *
 * <p>26.2 : le HUD a quitté {@code Gui} pour {@code Hud}, et
 * {@code ContextualBarRenderer} s'appelle {@code ContextualBar} — même
 * méthode, même descripteur, même position dans
 * {@code extractHotbarAndDecorations} (vérifié par javap).
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
abstract class HudExtractContextualBarBackgroundMixin263 {

    @WrapOperation(method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void la$dispatchContextualBarBackground(ContextualBar instance, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND, graphics)) {
            renderVanilla.call(instance, graphics, deltaTracker);
        }
    }
}
