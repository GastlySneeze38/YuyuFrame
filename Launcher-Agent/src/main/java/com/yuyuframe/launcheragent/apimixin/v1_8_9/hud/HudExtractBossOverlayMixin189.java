package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#HUD_EXTRACT_BOSS_OVERLAY} sur 1.8.9 — {@code InGameHud.renderBossBar()} (barre unique de cette version). */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractBossOverlayMixin189 {

    @Inject(method = "renderBossBar()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchBossOverlay(CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_BOSS_OVERLAY, null)) {
            ci.cancel();
        }
    }
}
