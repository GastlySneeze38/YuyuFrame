package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * {@link HookPoint#HUD_EXTRACT_AIR_BUBBLES} sur 1.8.9 — section {@code "air"},
 * dernière de {@code InGameHud.renderStatusBars} : pas de borne de fin, la
 * tranche court jusqu'au bout de la méthode. Voir {@code HudExtractArmorMixin189}
 * pour le choix du site d'appel.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractAirBubblesMixin189 {

    @WrapWithCondition(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V"),
        slice = @Slice(
            from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=air")),
        require = 0)
    private boolean la$dispatchAirBubbles(Object hud, int x, int y, int u, int v, int width, int height) {
        return !VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_AIR_BUBBLES, null);
    }
}
