package com.yuyuframe.launcheragent.apimixin.v26_2.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapHeldItemTooltip} vers {@link HookPoint#HUD_EXTRACT_SELECTED_ITEM_NAME}
 * — voir {@link HudExtractCameraOverlayMixin262} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
abstract class HudExtractSelectedItemNameMixin262 {

    @Inject(method = "extractSelectedItemName(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchSelectedItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME, graphics)) {
            ci.cancel();
        }
    }
}
