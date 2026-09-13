package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CONTAINER_SCREEN_EXTRACT_TOOLTIP} sur 1.8.9 — juste après le
 * fond d'un écran.
 *
 * <p>En 1.8.9 le fond n'est pas dessiné par une méthode commune de rendu
 * (chaque écran appelle lui-même {@code renderBackground()} dans son
 * {@code render}) : on vise la fin de {@code Screen.renderBackground(int)},
 * où aboutissent tous ces appels. {@code ctx} = l'écran (pas de contexte de
 * dessin sur cette version).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class ScreenAfterBackgroundExtractMixin189 {

    @Inject(method = "renderBackground(I)V", at = @At("TAIL"), require = 0)
    private void la$dispatchAfterBackground(int offset, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP, this);
    }
}
