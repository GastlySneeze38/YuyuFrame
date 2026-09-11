package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_CROSSHAIR} sur 1.21.11 —
 * {@code InGameHud.renderCrosshair(DrawContext, RenderTickCounter)}, pendant de
 * {@code Gui.extractCrosshair} en 26.1.2.
 *
 * <p>Remplace {@code mixin.client.CrosshairMixin}, qui lisait
 * {@code ModuleRegistry.get("custom-crosshair")} en dur : la décision
 * revient désormais au module ({@code CrosshairModule} s'enregistre sur ce
 * HookPoint et renvoie {@code isEnabled()}), comme en 26.1.2.
 *
 * <p>Même forme que le mixin 26.1.2, et pour la même raison : injecter dans la
 * MÉTHODE APPELÉE (HEAD, {@code cancellable}), jamais sur un site d'appel —
 * voir la javadoc de {@code HudExtractCrosshairMixin261} (Iris enveloppe la
 * méthode englobante et fait disparaître les sites d'appel).
 *
 * <p>Paramètres capturés en {@link Coerce} {@code Object} : leurs types réels
 * sont obfusqués, et nos stubs de compilation ne leur correspondent pas —
 * Sponge exigerait sinon une correspondance exacte (voir la leçon
 * {@code ClearOverlaysMixin} dans module-bracket-audit.md). Le contexte de
 * dessin est transmis tel quel, comme en 26.1.2.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractCrosshairMixin1211 {

    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchCrosshair(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_CROSSHAIR, context)) {
            ci.cancel();
        }
    }
}
