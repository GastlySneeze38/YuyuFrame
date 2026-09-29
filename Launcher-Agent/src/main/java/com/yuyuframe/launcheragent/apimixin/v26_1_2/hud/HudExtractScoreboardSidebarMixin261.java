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
 * Porte {@code GuiMixin#wrapScoreboardSidebar} vers {@link HookPoint#HUD_EXTRACT_SCOREBOARD_SIDEBAR}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractScoreboardSidebarMixin261 {

    @Inject(method = "extractScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchScoreboardSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR, graphics)) {
            ci.cancel();
        }
    }
}
