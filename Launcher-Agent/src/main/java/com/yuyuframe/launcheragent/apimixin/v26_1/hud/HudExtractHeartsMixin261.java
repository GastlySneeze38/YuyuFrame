package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapHealthBar} vers {@link HookPoint#HUD_EXTRACT_HEARTS}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du pattern.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractHeartsMixin261 {

    @WrapOperation(method = "extractPlayerHealth",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
    private void la$dispatchHearts(Gui instance, GuiGraphicsExtractor graphics, Player player, int x, int y, int lines,
                                    int regeneratingHeartIndex, float maxHealth, int lastHealth, int health,
                                    int absorption, boolean blinking, Operation<Void> renderVanilla) {
        VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_HEARTS, graphics);
        renderVanilla.call(instance, graphics, player, x, y, lines, regeneratingHeartIndex, maxHealth, lastHealth, health, absorption, blinking);
    }
}
