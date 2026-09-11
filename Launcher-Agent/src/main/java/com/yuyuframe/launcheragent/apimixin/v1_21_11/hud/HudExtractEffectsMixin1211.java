package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_EFFECTS} sur 1.21.11 — pendant de
 * {@code HudExtractEffectsMixin261}.
 *
 * <pre>
 *   26.1.2 : Gui.extractEffects(GuiGraphicsExtractor, DeltaTracker)
 *   1.21.11 : InGameHud.renderStatusEffectOverlay(DrawContext, RenderTickCounter)
 * </pre>
 *
 * {@code PotionEffectsModule} s'y enregistre et renvoie {@code isEnabled()} :
 * activé, il masque les icônes d'effet vanilla au profit des siennes. Même
 * forme que tout le HUD : HEAD + {@code cancellable} sur la méthode appelée,
 * paramètres en {@link Coerce} {@code Object}, contexte de dessin transmis.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractEffectsMixin1211 {

    @Inject(method = "renderStatusEffectOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchEffects(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_EFFECTS, context)) {
            ci.cancel();
        }
    }
}
