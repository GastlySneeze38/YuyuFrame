package com.yuyuframe.launcheragent.apimixin.v1_21_11.render;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ITEM_DECORATIONS_EXTRACT} sur 1.21.11 — pendant de
 * {@code ItemDecorationsExtractMixin261}.
 *
 * <pre>
 *   26.1.2 : GuiGraphicsExtractor.itemDecorations(Font, ItemStack, int, int, String), RETURN
 *   1.21.11 : DrawContext.drawStackOverlay(TextRenderer, ItemStack, int, int, String), RETURN
 * </pre>
 *
 * Mojang {@code renderItemDecorations} ; la surcharge sans {@code String}
 * délègue à celle-ci, qu'on vise seule (descripteur complet) pour ne pas
 * dispatcher deux fois.
 */
@Mixin(targets = "net.minecraft.client.gui.DrawContext")
public abstract class ItemDecorationsExtractMixin1211 {

    @Inject(method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("RETURN"), require = 0)
    private void la$dispatchItemDecorations(@Coerce Object textRenderer, @Coerce Object stack,
                                            int x, int y, String stackCountText, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_DECORATIONS_EXTRACT, this);
    }
}
