package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#HUD_EXTRACT_ITEM_HOTBAR} sur 1.8.9 — {@code InGameHud.renderHotbar(Window, float)}. */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractItemHotbarMixin189 {

    @Inject(method = "renderHotbar(Lnet/minecraft/client/util/Window;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchItemHotbar(@Coerce Object window, float tickDelta, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ITEM_HOTBAR, null)) {
            ci.cancel();
        }
    }
}
