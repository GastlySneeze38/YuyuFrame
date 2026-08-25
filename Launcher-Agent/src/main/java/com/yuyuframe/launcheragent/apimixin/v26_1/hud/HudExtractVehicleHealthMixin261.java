package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapMountHealth} vers {@link HookPoint#HUD_EXTRACT_VEHICLE_HEALTH}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du
 * pattern. Original Fabric capturait aussi {@code DeltaTracker} via
 * {@code @Local(argsOnly = true)} — omis ici (voir {@link
 * HudExtractSpectatorHotbarMixin261} pour le même choix).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractVehicleHealthMixin261 {

    @Inject(method = "extractVehicleHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchVehicleHealth(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_VEHICLE_HEALTH, graphics)) {
            ci.cancel();
        }
    }
}
