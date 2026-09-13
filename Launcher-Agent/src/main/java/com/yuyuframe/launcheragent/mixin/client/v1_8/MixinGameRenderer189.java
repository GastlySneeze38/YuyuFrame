package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
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
 * annulée via un @Inject cancellable en HEAD.
 *
 * CORRECTIF (session de débogage sprint/sneak) : {@code method=} en nom Yarn
 * ("updateMovementFovMultiplier()V"/"bobViewWhenHurt(F)V") ne se résolvait
 * JAMAIS dans ce bootstrap Mixin custom — confirmé en jeu via le logger Mixin
 * enfin relié à notre fichier de log (voir LauncherLogger) :
 * "InvalidInjectionException ... could not find any targets matching
 * 'updateMovementFovMultiplier()V' in bfk" — CE Mixin n'a donc probablement
 * JAMAIS fonctionné depuis sa création (le FOV de base se réglait bien via
 * réflexion directe, mais le multiplicateur sprint/ralenti n'était jamais
 * neutralisé, d'où "le FOV ne marche pas" en bougeant). Remplacé par les
 * lettres officielles directes ("l"/"d", vérifiées via javap sur le vrai
 * bfk.class), même correctif que MixinToggleSprint189/MixinToggleSneak189.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class MixinGameRenderer189 {

    // Noms de champs Java = lettres officielles DIRECTES ("x"/"y"), même
    // raison que pour method= : @Shadow passe par le même remapping yarn qui
    // ne fonctionne pas dans ce bootstrap — movementFovMultiplier="x",
    // lastMovementFovMultiplier="y" (vérifiés via javap sur bfk.class).
    @Shadow private float x;
    @Shadow private float y;

    @Inject(method = "l()V", at = @At("TAIL"))
    private void la$neutralizeSprintFov(CallbackInfo ci) {
        try {
            LauncherModule fov = ModuleRegistry.get("fov");
            if (fov == null || !fov.isEnabled()) return;
            this.x = 1.0f;
            this.y = 1.0f;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinGameRenderer189 la$neutralizeSprintFov: " + t);
        }
    }

    @Inject(method = "d(F)V", at = @At("HEAD"), cancellable = true)
    private void la$cancelHurtCam(float partialTicks, CallbackInfo ci) {
        try {
            LauncherModule hurtCam = ModuleRegistry.get("hurt-cam");
            if (hurtCam != null && hurtCam.isEnabled()) ci.cancel();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinGameRenderer189 la$cancelHurtCam: " + t);
        }
    }
}
