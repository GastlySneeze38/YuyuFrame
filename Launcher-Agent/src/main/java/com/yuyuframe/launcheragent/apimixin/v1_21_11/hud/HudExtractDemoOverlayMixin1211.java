package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_DEMO_OVERLAY} sur 1.21.11 —
 * {@code InGameHud.renderDemoTimer} (Mojang {@code renderDemoOverlay}).
 * Forme : voir {@link HudExtractCameraOverlayMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractDemoOverlayMixin1211 {

    @Inject(method = "renderDemoTimer(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchDemoOverlay(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_DEMO_OVERLAY, context)) {
            ci.cancel();
        }
    }
}
