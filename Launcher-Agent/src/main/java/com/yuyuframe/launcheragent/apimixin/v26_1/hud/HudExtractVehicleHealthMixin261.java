package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapMountHealth} vers {@link HookPoint#HUD_EXTRACT_VEHICLE_HEALTH}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du
 * pattern. Original Fabric capturait aussi {@code DeltaTracker} via
 * {@code @Local(argsOnly = true)} — omis ici (voir {@link
 * HudExtractSpectatorHotbarMixin261} pour le même choix).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractVehicleHealthMixin261 {

    @WrapOperation(method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractVehicleHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void la$dispatchVehicleHealth(Gui instance, GuiGraphicsExtractor graphics, Operation<Void> renderVanilla) {
        VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_VEHICLE_HEALTH, graphics);
        renderVanilla.call(instance, graphics);
    }
}
