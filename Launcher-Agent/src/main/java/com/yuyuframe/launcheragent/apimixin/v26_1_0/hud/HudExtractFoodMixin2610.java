package com.yuyuframe.launcheragent.apimixin.v26_1_0.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapFoodBar} vers {@link HookPoint#HUD_EXTRACT_FOOD}
 * — voir {@link HudExtractCameraOverlayMixin2610} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractFoodMixin2610 {

    @Inject(method = "extractFood(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;II)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchFood(GuiGraphicsExtractor graphics, Player player, int top, int right, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_FOOD, graphics)) {
            ci.cancel();
        }
    }
}
