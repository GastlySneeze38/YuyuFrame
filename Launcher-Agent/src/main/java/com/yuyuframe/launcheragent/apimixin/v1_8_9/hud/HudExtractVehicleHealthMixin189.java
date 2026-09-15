package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * {@link HookPoint#HUD_EXTRACT_VEHICLE_HEALTH} sur 1.8.9 — section
 * {@code "mountHealth"} de {@code InGameHud.renderStatusBars}. Voir
 * {@code HudExtractArmorMixin189} pour le choix du site d'appel et de la
 * largeur mise à zéro.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud")
public abstract class HudExtractVehicleHealthMixin189 {

    @ModifyArg(method = "renderStatusBars(Lnet/minecraft/client/util/Window;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V"),
        slice = @Slice(
            from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=mountHealth"),
            to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=air")),
        index = 4, require = 0)
    private int la$dispatchVehicleHealth(int width) {
        return VanillaHookRegistry.dispatch(HookPoint.HUD_EXTRACT_VEHICLE_HEALTH, null) ? 0 : width;
    }
}
