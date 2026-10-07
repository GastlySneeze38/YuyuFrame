package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Le voile bleu plaqué sur l'écran quand la tête est sous l'eau
 * ({@code HeldItemRenderer.renderUnderwaterOverlay(float)}, {@code bfn.e(F)V},
 * texture {@code textures/misc/underwater.png}) — second voile plein écran de
 * la 1.8.9 après la citrouille, servi par le même point d'accroche
 * {@link HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY}, avec son chemin de texture.
 *
 * <p>Sur les versions récentes, la vision sous l'eau est entièrement une
 * affaire de brouillard ; ici, le repousser ne suffit pas : ce voile est
 * dessiné à part, par-dessus.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class UnderwaterOverlayMixin189 {

    private static final String UNDERWATER_TEXTURE_PATH = "textures/misc/underwater.png";

    @Inject(method = "renderUnderwaterOverlay(F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchUnderwaterOverlay(float tickDelta, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY, UNDERWATER_TEXTURE_PATH)) {
            ci.cancel();
        }
    }
}
