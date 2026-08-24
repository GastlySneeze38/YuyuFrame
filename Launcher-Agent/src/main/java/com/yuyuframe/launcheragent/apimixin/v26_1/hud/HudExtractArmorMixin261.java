package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code GuiMixin#wrapArmorBar} vers {@link HookPoint#HUD_EXTRACT_ARMOR}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du
 * pattern. {@code Gui.extractArmor} est STATIQUE (contrairement à la plupart
 * des autres extractions) — pas de paramètre {@code instance} en tête ici,
 * même chose côté Fabric d'origine (vérifié dans le source).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractArmorMixin261 {

    @WrapOperation(method = "extractPlayerHealth",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractArmor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIII)V"))
    private void la$dispatchArmor(GuiGraphicsExtractor graphics, Player player, int i, int j, int k, int x, Operation<Void> renderVanilla) {
        VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ARMOR, graphics);
        renderVanilla.call(graphics, player, i, j, k, x);
    }
}
