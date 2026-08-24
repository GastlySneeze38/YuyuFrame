package com.yuyuframe.launcheragent.apimixin.v26_1.render;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GuiGraphicsExtractorMixin#drawStackOverlay} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#ITEM_DECORATIONS_EXTRACT} —
 * texte de durabilité/quantité d'un item, vanilla inclus (PAS un point
 * d'enregistrement de contenu custom).
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor")
abstract class ItemDecorationsExtractMixin261 {

    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("RETURN"))
    private void la$dispatchItemDecorations(Font font, ItemStack stack, int x, int y, String stackCountText, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_DECORATIONS_EXTRACT, this);
    }
}
