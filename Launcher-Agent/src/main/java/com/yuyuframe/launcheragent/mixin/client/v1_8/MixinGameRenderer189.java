package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.modules.LauncherModule;
import com.yuyuframe.launcheragent.runtime.modules.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Deuxième Mixin ciblant GameRenderer (le premier, voir GlobalUiRenderMixin189,
 * gère notre moteur UI custom — celui-ci gère 2 réglages issus de PvP-Mod qui
 * n'ont pas d'équivalent sans hook direct dans le bytecode vanilla : le FOV
 * sprint/ralenti et le hurt cam.
 *
 * FOV : movementFovMultiplier/lastMovementFovMultiplier sont recalculés une
 * fois par tick par updateMovementFovMultiplier() — les neutraliser depuis
 * l'extérieur (notre propre boucle de rendu) perdrait systématiquement la
 * course contre cet appel interne, d'où l'injection directement en TAIL de
 * cette méthode (même raisonnement que PvP-Mod/mixin/MixinEntityRenderer.java,
 * son unique Mixin). Champs vérifiés dans mappings-1.8.9.tiny sur GameRenderer
 * (officiel "bfk") : movementFovMultiplier="x", lastMovementFovMultiplier="y",
 * updateMovementFovMultiplier="l".
 *
 * Hurt cam : bobViewWhenHurt (officiel "d") applique une rotation OpenGL
 * directe (immediate-mode, pas de valeur de retour à modifier après coup) —
 * annulée via un @Inject cancellable en HEAD, même pattern déjà utilisé par
 * EntityCullingMixin dans ce projet (donc pas une nouvelle capacité, juste
 * une nouvelle cible).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class MixinGameRenderer189 {

    @Shadow private float movementFovMultiplier;
    @Shadow private float lastMovementFovMultiplier;

    @Inject(method = "updateMovementFovMultiplier()V", at = @At("TAIL"))
    private void la$neutralizeSprintFov(CallbackInfo ci) {
        try {
            LauncherModule fov = ModuleRegistry.get("fov");
            if (fov == null || !fov.isEnabled()) return;
            this.movementFovMultiplier = 1.0f;
            this.lastMovementFovMultiplier = 1.0f;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinGameRenderer189 la$neutralizeSprintFov: " + t);
        }
    }

    @Inject(method = "bobViewWhenHurt(F)V", at = @At("HEAD"), cancellable = true)
    private void la$cancelHurtCam(float partialTicks, CallbackInfo ci) {
        try {
            LauncherModule hurtCam = ModuleRegistry.get("hurt-cam");
            if (hurtCam != null && hurtCam.isEnabled()) ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinGameRenderer189 la$cancelHurtCam: " + t);
        }
    }
}
