package com.yuyuframe.launcheragent.apimixin.v26_1.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiMixin#wrapArmorBar} vers {@link HookPoint#HUD_EXTRACT_ARMOR}
 * — voir {@link HudExtractCameraOverlayMixin261} pour l'explication du
 * pattern. {@code Gui.extractArmor} est STATIQUE (contrairement à la plupart
 * des autres extractions) — pas de paramètre {@code instance} en tête ici,
 * même chose côté Fabric d'origine (vérifié dans le source).
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
abstract class HudExtractArmorMixin261 {

    @Inject(method = "extractArmor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIII)V", at = @At("HEAD"), cancellable = true)
    private void la$dispatchArmor(GuiGraphicsExtractor graphics, Player player, int i, int j, int k, int x, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_ARMOR, graphics)) {
            ci.cancel();
        }
    }
}
