package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#HUD_EXTRACT_SCOREBOARD_SIDEBAR} sur 1.8.9 — {@code InGameHud.renderScoreboardObjective(ScoreboardObjective, Window)}. */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractScoreboardSidebarMixin189 {

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchScoreboardSidebar(@Coerce Object objective, @Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR, null)) {
            ci.cancel();
        }
    }
}
