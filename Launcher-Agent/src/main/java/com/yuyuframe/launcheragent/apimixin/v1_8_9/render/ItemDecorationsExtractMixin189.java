package com.yuyuframe.launcheragent.apimixin.v1_8_9.render;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ITEM_DECORATIONS_EXTRACT} sur 1.8.9 — pendant de
 * {@code ItemDecorationsExtractMixin1211} :
 * {@code ItemRenderer.renderGuiItemOverlay(TextRenderer, ItemStack, int, int, String)},
 * RETURN (quantité et durabilité déjà dessinées). La surcharge sans texte y délègue.
 */
@Mixin(targets = "net.minecraft.client.render.item.ItemRenderer")
public abstract class ItemDecorationsExtractMixin189 {

    @Inject(method = "renderGuiItemOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("RETURN"), require = 0)
    private void la$dispatchItemDecorations(@Coerce Object textRenderer, @Coerce Object stack,
                                            int x, int y, String countText, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_DECORATIONS_EXTRACT, this);
    }
}
