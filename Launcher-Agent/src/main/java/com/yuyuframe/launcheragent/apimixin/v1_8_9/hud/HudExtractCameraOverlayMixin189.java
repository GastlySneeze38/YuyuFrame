package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_CAMERA_OVERLAY} sur 1.8.9 — les overlays de caméra
 * de cette version : vignette ({@code renderVignetteOverlay}) et portail
 * ({@code renderNausea}). La citrouille a son propre HookPoint
 * ({@code HudExtractTextureOverlayMixin189}).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractCameraOverlayMixin189 {

    @Inject(method = "renderVignetteOverlay(FLnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchVignette(float brightness, @Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CAMERA_OVERLAY, null)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderNausea(FLnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchPortal(float strength, @Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CAMERA_OVERLAY, null)) {
            ci.cancel();
        }
    }
}
