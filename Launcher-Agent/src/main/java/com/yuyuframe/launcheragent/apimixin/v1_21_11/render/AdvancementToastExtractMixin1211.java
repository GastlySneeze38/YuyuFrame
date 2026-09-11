package com.yuyuframe.launcheragent.apimixin.v1_21_11.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#ADVANCEMENT_TOAST_EXTRACT} sur 1.21.11 — pendant de
 * {@code AdvancementToastExtractMixin261}.
 *
 * <pre>
 *   26.1.2 : AdvancementToast.extractRenderState → GuiGraphicsExtractor.fakeItem(ItemStack, int, int)
 *   1.21.11 : AdvancementToast.draw               → DrawContext.drawItemWithoutEntity(ItemStack, int, int)
 * </pre>
 *
 * Vérifié javap : {@code gmx.a(gir,gio,J)} appelle {@code gir.b(dlt,II)} une
 * seule fois. {@code draw} n'est déclarée dans Yarn que sur {@code Toast} :
 * l'entrée de refmap porte ce parent en repli. Receveur en {@code Object} :
 * même pari que {@code MouseHandlerFreelookMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.toast.AdvancementToast")
public abstract class AdvancementToastExtractMixin1211 {

    @WrapOperation(method = "draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;J)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V"),
        require = 0)
    private void la$dispatchAdvancementToast(Object context, Object icon, int x, int y, Operation<Void> original) {
        VanillaHookRegistry.dispatch(HookPoint.ADVANCEMENT_TOAST_EXTRACT, context);
        original.call(context, icon, x, y);
    }
}
