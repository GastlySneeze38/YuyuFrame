package com.yuyuframe.launcheragent.apimixin.v26_1_2.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Porte {@code SubtitleOverlayMixin#wrapExtractRenderState} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#SUBTITLE_OVERLAY_EXTRACT} —
 * {@code @WrapMethod} (MixinExtras) enveloppe la méthode ENTIÈRE, pas un seul
 * appel interne comme {@code @WrapOperation}.
 */
@Mixin(targets = "net.minecraft.client.gui.components.SubtitleOverlay")
abstract class SubtitleOverlayExtractMixin261 {

    @WrapMethod(method = "extractRenderState")
    private void la$dispatchSubtitleOverlay(GuiGraphicsExtractor context, Operation<Void> original) {
        VanillaHookRegistry.dispatch(HookPoint.SUBTITLE_OVERLAY_EXTRACT, context);
        original.call(context);
    }
}
