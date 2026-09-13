package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#HUD_EXTRACT_DEMO_OVERLAY} sur 1.8.9 — {@code InGameHud.renderDemoTime(Window)}. */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractDemoOverlayMixin189 {

    @Inject(method = "renderDemoTime(Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchDemoOverlay(@Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_DEMO_OVERLAY, null)) {
            ci.cancel();
        }
    }
}
