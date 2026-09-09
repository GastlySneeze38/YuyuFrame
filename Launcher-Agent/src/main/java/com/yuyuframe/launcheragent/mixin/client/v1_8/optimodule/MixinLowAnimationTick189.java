package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cible : net.minecraft.client.texture.SpriteAtlasTexture (official bmh),
 * méthode update()V (officiel "d", method_5238) — appelée une fois par tick
 * client, avance chaque sprite animé (eau, lave, feu, portails...) d'une
 * frame. Annule un appel sur deux quand actif — divise la fréquence de mise
 * à jour par 2 sans rien changer d'autre.
 */
@Mixin(targets = "net.minecraft.client.texture.SpriteAtlasTexture")
public abstract class MixinLowAnimationTick189 {

    private static int la$tickCounter;

    @Inject(method = "d()V", at = @At("HEAD"), cancellable = true)
    private void la$skipEveryOtherTick(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("low-animation-tick");
            if (module == null || !module.isEnabled()) return;
            la$tickCounter++;
            if ((la$tickCounter & 1) == 1) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLowAnimationTick189: " + t);
        }
    }
}
