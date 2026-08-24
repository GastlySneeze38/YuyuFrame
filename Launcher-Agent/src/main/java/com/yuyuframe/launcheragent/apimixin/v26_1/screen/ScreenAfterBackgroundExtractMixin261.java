package com.yuyuframe.launcheragent.apimixin.v26_1.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code ScreenMixin#extractWithTooltip} (fabric-screen-api-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#CONTAINER_SCREEN_EXTRACT_TOOLTIP} —
 * CORRECTIF de classification : cible en réalité {@code Screen} en général
 * (juste après {@code extractBackground}), pas spécifiquement
 * {@code AbstractContainerScreen} comme le nom du HookPoint le suggère (à
 * corriger dans {@code HookPoint.java}) — c'est là que
 * {@code ShulkerPreviewModule} devra s'accrocher, {@code AbstractContainerScreen}
 * en étant juste un cas particulier.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
abstract class ScreenAfterBackgroundExtractMixin261 {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", shift = At.Shift.AFTER))
    private void la$dispatchAfterBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP, graphics);
    }
}
