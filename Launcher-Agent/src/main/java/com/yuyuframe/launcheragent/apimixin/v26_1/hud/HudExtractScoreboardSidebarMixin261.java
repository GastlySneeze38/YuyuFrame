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
 * Porte {@code GuiMixin#wrapScoreboardSidebar} vers {@link HookPoint#HUD_EXTRACT_SCOREBOARD_SIDEBAR}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractScoreboardSidebarMixin261 {

    @WrapOperation(method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void la$dispatchScoreboardSidebar(Gui instance, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR, graphics)) {
            renderVanilla.call(instance, graphics, deltaTracker);
        }
    }
}
