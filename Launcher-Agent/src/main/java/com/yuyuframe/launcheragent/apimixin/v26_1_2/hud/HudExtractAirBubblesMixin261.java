package com.yuyuframe.launcheragent.apimixin.v26_1_2.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapAirBar} vers {@link HookPoint#HUD_EXTRACT_AIR_BUBBLES}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractAirBubblesMixin261 {

    @Inject(method = "extractAirBubbles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;III)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchAirBubbles(GuiGraphicsExtractor graphics, Player player, int heartCount, int top, int left, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_AIR_BUBBLES, graphics)) {
            ci.cancel();
        }
    }
}
