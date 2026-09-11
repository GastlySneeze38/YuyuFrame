package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_SELECTED_ITEM_NAME} sur 1.21.11 —
 * {@code InGameHud.renderHeldItemTooltip} (Mojang {@code renderSelectedItemName}).
 * Forme : voir {@link HudExtractCameraOverlayMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractSelectedItemNameMixin1211 {

    @Inject(method = "renderHeldItemTooltip(Lnet/minecraft/client/gui/DrawContext;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchSelectedItemName(@Coerce Object context, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME, context)) {
            ci.cancel();
        }
    }
}
