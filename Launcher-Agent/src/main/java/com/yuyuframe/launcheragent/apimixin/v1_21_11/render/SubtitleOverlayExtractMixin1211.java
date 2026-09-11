package com.yuyuframe.launcheragent.apimixin.v1_21_11.render;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#SUBTITLE_OVERLAY_EXTRACT} sur 1.21.11 — pendant de
 * {@code SubtitleOverlayExtractMixin261}.
 *
 * <pre>
 *   26.1.2 : SubtitleOverlay.extractRenderState(GuiGraphicsExtractor)
 *   1.21.11 : SubtitlesHud.render(DrawContext)
 * </pre>
 *
 * 26.1.2 utilise {@code @WrapMethod} mais n'en tire rien d'autre que
 * « dispatcher puis appeler l'original » — un {@code @Inject} HEAD fait
 * exactement la même chose, par le cœur de Mixin et sans receveur typé.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.SubtitlesHud")
public abstract class SubtitleOverlayExtractMixin1211 {

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), require = 0)
    private void la$dispatchSubtitleOverlay(@Coerce Object context, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SUBTITLE_OVERLAY_EXTRACT, context);
    }
}
