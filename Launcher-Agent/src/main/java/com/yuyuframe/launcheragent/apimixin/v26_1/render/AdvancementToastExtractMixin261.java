package com.yuyuframe.launcheragent.apimixin.v26_1.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte {@code AdvancementToastMixin#extractAdvancementIcon} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#ADVANCEMENT_TOAST_EXTRACT} —
 * hooke TOUS les toasts (vanilla compris), pas juste ceux d'un progrès custom.
 * L'original Fabric route ici vers {@code AdvancementRendererRegistryImpl}
 * (icône custom PAR progrès enregistré) — non reproduit, dispatch simple +
 * dessin vanilla toujours effectué (voir {@code apimixin/v26_1/hud/HudExtractCameraOverlayMixin261}
 * pour le raisonnement complet de cette simplification).
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.AdvancementToast")
abstract class AdvancementToastExtractMixin261 {

    @WrapOperation(method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void la$dispatchAdvancementToast(GuiGraphicsExtractor graphics, ItemStack icon, int x, int y, Operation<Void> original) {
        VanillaHookRegistry.dispatch(HookPoint.ADVANCEMENT_TOAST_EXTRACT, graphics);
        original.call(graphics, icon, x, y);
    }
}
