package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_HEARTS} sur 1.21.11 —
 * {@code InGameHud.renderHealthBar} (Mojang {@code renderHearts}). Forme :
 * voir {@link HudExtractCameraOverlayMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractHeartsMixin1211 {

    @Inject(method = "renderHealthBar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchHearts(@Coerce Object context, @Coerce Object player, int x, int y, int lines,
                                   int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                   int absorption, boolean blinking, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_HEARTS, context)) {
            ci.cancel();
        }
    }
}
