package com.yuyuframe.launcheragent.apimixin.v26_1.freelook;

import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Équivalent apimixin de l'ancien {@code mixin.client.v26_1.CameraFreelookMixin261}
 * (voir ce fichier pour l'historique complet des 3 bugs trouvés — ordre
 * yRot/xRot, orbite sur soi-même, clipping mur — le mixin/ historique reste
 * actif et inchangé, ceci est construit en parallèle, non branché).
 *
 * Remplace 2 hooks + réflexion manuelle (getDeclaredField/getDeclaredMethod
 * pour xRot/yRot/detached/setRotation) par UN SEUL {@code @ModifyArgs} (core
 * Sponge Mixin, PAS MixinExtras — disponible nativement) sur l'appel
 * {@code Camera.setRotation(F,F)} DANS {@code alignWithEntity} — technique
 * reprise de Omnilook ({@code dev.rdh.omnilook.mixin.fabric.CameraMixin},
 * référence externe déjà étudiée pour ce portage, voir audit ROADMAP-agent.md
 * §3.3).
 *
 * ZÉRO réflexion : {@link Args} expose les arguments déjà capturés par
 * Mixin au point d'injection, aucun champ à lire/écrire à la main. Résout
 * les 3 bugs de l'ancienne version PAR CONSTRUCTION :
 * <ul>
 *   <li>BUG #1 (ordre yRot/xRot) : {@code args.get(0)}/{@code args.get(1)}
 *       suivent l'ordre RÉEL des arguments au bytecode, aucune supposition
 *       possible contrairement à un appel reflète manuellement.</li>
 *   <li>BUG #2/#3 (orbite sur soi-même / clipping mur) : {@code setRotation()}
 *       reste appelé au MÊME point qu'avant dans {@code alignWithEntity}
 *       (avant {@code getMaxZoom}/{@code move()}) — {@code @ModifyArgs} ne
 *       déplace jamais l'appel, seulement ses arguments — donc {@code
 *       this.forwards} est à jour AVANT le raycast de {@code getMaxZoom},
 *       exactement comme le fix manuel de l'ancienne version l'obtenait.</li>
 * </ul>
 *
 * HYPOTHÈSE (à vérifier en jeu, voir Omnilook qui fait le même pari) : un
 * seul hook couvre 1re ET 3e personne — {@code alignWithEntity} serait le
 * point d'entrée caméra appelé chaque frame quel que soit le mode de vue
 * ({@code setPosition}+{@code setRotation} inconditionnels, seul le recul
 * via {@code move()}/{@code getMaxZoom} est gardé par {@code detached}),
 * contrairement à l'ancienne version qui avait besoin de 2 hooks + garde
 * {@code !detached} faute d'en être sûr. NON TESTÉ EN JEU au moment de
 * l'écriture.
 */
@Mixin(targets = "net.minecraft.client.Camera")
public abstract class CameraFreelookMixin261 {

    @ModifyArgs(method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void la$applyFreelookOffset(Args args) {
        if (!FreelookModule.isActive()) return;
        float yRot = args.get(0);
        float xRot = args.get(1);
        float newXRot = clamp(xRot + (float) FreelookModule.pitchOffset(), -90f, 90f);
        float newYRot = yRot + (float) FreelookModule.yawOffset();
        args.set(0, newYRot);
        args.set(1, newXRot);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
