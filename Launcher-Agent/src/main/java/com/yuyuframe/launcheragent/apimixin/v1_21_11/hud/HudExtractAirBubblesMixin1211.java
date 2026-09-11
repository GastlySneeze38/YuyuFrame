package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_AIR_BUBBLES} sur 1.21.11 —
 * {@code InGameHud.renderAirBubbles}. Forme : voir {@link HudExtractCameraOverlayMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractAirBubblesMixin1211 {

    @Inject(method = "renderAirBubbles(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;III)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchAirBubbles(@Coerce Object context, @Coerce Object player,
                                       int heartCount, int top, int left, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_AIR_BUBBLES, context)) {
            ci.cancel();
        }
    }
}
