package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND} sur 1.21.11 —
 * pendant de {@code HudExtractContextualBarBackgroundMixin261}.
 *
 * <pre>
 *   26.1.2 : Gui.extractHotbarAndDecorations → ContextualBarRenderer.extractBackground
 *   1.21.11 : InGameHud.renderMainHud          → Bar.renderBar
 * </pre>
 *
 * Site d'appel vérifié par javap : {@code invokeinterface gnf.a(gir,gez)}
 * dans {@code giq.m(gir,gez)} — le premier des deux appels {@code gnf}
 * d'instance, comme en 26.1.2.
 *
 * <p>Receveur typé {@code Object} : même pari que
 * {@code MouseHandlerFreelookMixin1211} (MixinExtras sans {@code @Coerce}),
 * à lever par le test du freelook. Gaté : tant qu'aucun module ne réclame ce
 * HookPoint, ce mixin n'est pas tissé.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractContextualBarBackgroundMixin1211 {

    @WrapOperation(method = "renderMainHud(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/bar/Bar;renderBar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"),
        require = 0)
    private void la$dispatchContextualBarBackground(Object bar, Object context, Object tickCounter,
                                                    Operation<Void> renderVanilla) {
        if (!VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND, context)) {
            renderVanilla.call(bar, context, tickCounter);
        }
    }
}
