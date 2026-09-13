package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_SPECTATOR_ACTION} sur 1.8.9 — nom de l'action
 * spectateur sélectionnée, {@code SpectatorHud.render(Window)} (appelée par
 * {@code InGameHud.render} à la place du nom de l'objet tenu).
 */
@Mixin(targets = "net.minecraft.client.gui.hud.SpectatorHud")
public abstract class HudExtractSpectatorActionMixin189 {

    @Inject(method = "render(Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchSpectatorAction(@Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_SPECTATOR_ACTION, null)) {
            ci.cancel();
        }
    }
}
