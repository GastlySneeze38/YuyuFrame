package com.yuyuframe.launcheragent.apimixin.v26_2.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapHealthBar} vers {@link HookPoint#HUD_EXTRACT_HEARTS}
 * — voir {@link HudExtractCameraOverlayMixin262} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractHeartsMixin262 {

    @Inject(method = "extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchHearts(GuiGraphicsExtractor graphics, Player player, int x, int y, int lines,
                                    int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                    int absorption, boolean blinking, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_HEARTS, graphics)) {
            ci.cancel();
        }
    }
}
