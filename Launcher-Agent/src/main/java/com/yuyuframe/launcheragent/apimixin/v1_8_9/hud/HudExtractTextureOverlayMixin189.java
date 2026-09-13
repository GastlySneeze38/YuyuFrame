package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY} sur 1.8.9 — {@code InGameHud.renderPumpkinBlur(Window)}.
 *
 * <p>Seul overlay texturé plein écran de cette version (pas de neige poudreuse).
 * La texture est une constante de la méthode (javap {@code avo.e(avr)V} :
 * {@code textures/misc/pumpkinblur.png}) : le chemin est transmis tel quel,
 * sans point d'accès, comme le contrat {@code String} du HookPoint le prévoit.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractTextureOverlayMixin189 {

    private static final String PUMPKIN_TEXTURE_PATH = "textures/misc/pumpkinblur.png";

    @Inject(method = "renderPumpkinBlur(Lnet/minecraft/client/util/Window;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchPumpkinBlur(@Coerce Object window, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, PUMPKIN_TEXTURE_PATH)) {
            ci.cancel();
        }
    }
}
