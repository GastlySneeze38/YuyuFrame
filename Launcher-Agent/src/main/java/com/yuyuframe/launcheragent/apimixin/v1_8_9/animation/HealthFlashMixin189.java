package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@link HookPoint#HEALTH_BAR_FLASH} sur 1.8.9 —
 * {@code InGameHud.renderStatusBars(Window)V} ({@code avo.d(Lavr;)V}).
 *
 * <p>Vanilla 1.8 calcule en tête de méthode un booléen « clignote à cette
 * image » ({@code healthUpdateCounter > ticks && (écart / 3) % 2 == 1}, deux
 * {@code lcmp} puis {@code istore 4}, offset 74 au javap) : il éclaire le
 * contour des cœurs et redessine les cœurs perdus en blanc. La 1.7 n'avait pas
 * ce clignotement. C'est le premier stockage du local 4.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HealthFlashMixin189 {

    @ModifyVariable(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V",
        at = @At(value = "STORE", ordinal = 0), index = 4, require = 0)
    private boolean la$healthFlash(boolean flashing) {
        Object value = VanillaHookRegistry.dispatchValue(HookPoint.HEALTH_BAR_FLASH, Boolean.valueOf(flashing));
        return value instanceof Boolean ? (Boolean) value : flashing;
    }
}
