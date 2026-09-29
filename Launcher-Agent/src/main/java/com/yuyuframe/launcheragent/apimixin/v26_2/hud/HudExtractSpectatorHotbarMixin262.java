package com.yuyuframe.launcheragent.apimixin.v26_2.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapSpectatorMenu} vers {@link HookPoint#HUD_EXTRACT_HOTBAR}
 * — voir {@link HudExtractCameraOverlayMixin262} pour l'explication du
 * pattern. Original Fabric capturait aussi {@code DeltaTracker} via
 * {@code @Local(argsOnly = true)} — omis ici, notre ctx de dispatch ne porte
 * que {@code graphics} (voir la javadoc de {@link VanillaHookRegistry}).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractSpectatorHotbarMixin262 {

    @WrapOperation(method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/spectator/SpectatorGui;extractHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void la$dispatchSpectatorHotbar(SpectatorGui instance, GuiGraphicsExtractor graphics, Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_HOTBAR, graphics)) {
            renderVanilla.call(instance, graphics);
        }
    }
}
