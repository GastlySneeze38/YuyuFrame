package com.yuyuframe.launcheragent.apimixin.v26_1_2.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapDemoTimer} vers {@link HookPoint#HUD_EXTRACT_DEMO_OVERLAY}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractDemoOverlayMixin261 {

    @Inject(method = "extractDemoOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchDemoOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_DEMO_OVERLAY, graphics)) {
            ci.cancel();
        }
    }
}
