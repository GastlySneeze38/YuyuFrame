package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_HOTBAR} sur 1.8.9 — barre du mode spectateur,
 * {@code SpectatorHud.render(Window, float)} (appelée par {@code InGameHud.render}
 * à la place de la barre d'objets, javap {@code avo.a(F)V}).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.SpectatorHud")
public abstract class HudExtractSpectatorHotbarMixin189 {

    @Inject(method = "render(Lnet/minecraft/client/util/Window;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchSpectatorHotbar(@Coerce Object window, float tickDelta, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_HOTBAR, null)) {
            ci.cancel();
        }
    }
}
