package com.yuyuframe.launcheragent.apimixin.v1_21_11.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_SCOREBOARD_SIDEBAR} sur 1.21.11 —
 * {@code InGameHud.renderScoreboardSidebar(DrawContext, RenderTickCounter)}.
 *
 * <p>⚠️ Nom SURCHARGÉ : il existe aussi
 * {@code renderScoreboardSidebar(DrawContext, ScoreboardObjective)} (le
 * dessin effectif, appelé par celle-ci). On vise la même que 26.1.2, celle
 * de la passe HUD ; le descripteur complet départage (refmap et remappeur
 * tiennent compte du descripteur depuis v1052).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractScoreboardSidebarMixin1211 {

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchScoreboardSidebar(@Coerce Object context, @Coerce Object tickCounter, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR, context)) {
            ci.cancel();
        }
    }
}
