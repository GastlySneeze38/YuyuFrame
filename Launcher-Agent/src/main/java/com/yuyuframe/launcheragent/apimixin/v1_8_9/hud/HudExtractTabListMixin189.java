package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#HUD_EXTRACT_TAB_LIST} sur 1.8.9 — {@code PlayerListHud.render(int, Scoreboard, ScoreboardObjective)}. */
@Mixin(targets = "net.minecraft.client.gui.hud.PlayerListHud")
public abstract class HudExtractTabListMixin189 {

    @Inject(method = "render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchTabList(int width, @Coerce Object scoreboard, @Coerce Object objective, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TAB_LIST, null)) {
            ci.cancel();
        }
    }
}
