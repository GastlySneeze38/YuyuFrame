package com.yuyuframe.launcheragent.apimixin.v26_1.freelook;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
 *
 * Passe par {@link HookPoint#FREELOOK_CAMERA_ROTATION_OFFSET} (audit
 * ROADMAP-agent.md §4 — apimixin ne doit jamais importer un module concret
 * de {@code runtime.*}, ce fichier importait directement {@code
 * FreelookModule} avant cette correction) — {@code FreelookModule} calcule
 * l'offset et s'enregistre lui-même via {@code VanillaHookRegistry.registerValue}.
 *
 * ⚠️ {@code @ModifyArgs} (utilisé initialement) REMPLACÉ par {@code @Redirect}
 * (2026-08-24, voir [[project_mc_261_port]] §11) : {@code Camera} se charge
 * très tôt (dans {@code Minecraft.<init>}), avant que notre transformer Mixin
 * ne soit enregistré dans l'{@code Instrumentation} — donc TOUJOURS tissé via
 * {@code IsolatedBootstrap.scheduleDelayedRetransform} (retransform JVMTI a
 * posteriori, mécanisme normal de ce projet, RIEN à voir avec la gate
 * déclarative {@code HookPoint}/{@code MixinHookPointRegistry}, contrairement
 * à ce qu'on a d'abord cru). Sous ce chemin de retransform, la classe
 * synthétique générée par {@code ArgsClassGenerator} pour {@code @ModifyArgs}
 * ({@code Args$1}, mécanisme interne Sponge Mixin) ne se liait pas au runtime
 * → {@code NoSuchMethodError: Args$1.of(float,float)} systématique dans
 * {@code Camera.alignWithEntity}. {@code @Redirect} ne génère AUCUNE classe
 * annexe (simple substitution d'appel INVOKEVIRTUAL par un appel direct à
 * notre méthode) — insensible à ce bug, fonctionne identiquement sous
 * tissage initial ET sous retransform.
 *
 * ⚠️ {@link FabricKnotExposer#ensureExposed} appelé ici aussi (2026-08-24,
 * même chantier §11) — filet de sécurité SANS EFFET dans la pratique : ce
 * problème (résolution de {@code VanillaHookRegistry} par APP au lieu de
 * Knot, {@code LinkageError} sur {@code ValueHandler} au premier module
 * enregistré côté Knot) était en réalité causé par {@code
 * GameRenderExtractMixin261}/{@code ClockTotalTicksMixin261} — deux mixins
 * SANS garde, exécutés dès la 1re frame — pas par ce fichier (dont le
 * premier appel réel n'a lieu qu'à la connexion à un monde, largement après).
 * Le vrai fix vit dans {@code LauncherMixinTransformerWrapper.
 * triggerEarlyKnotExpose()}, déclenché dès la toute première classe Knot
 * transformée — voir sa javadoc. Appel laissé ici par prudence (idempotent,
 * sans coût après le premier succès), pas par nécessité.
 */
@Mixin(targets = "net.minecraft.client.Camera")
public abstract class CameraFreelookMixin261 {

    @Redirect(method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void la$applyFreelookOffset(Camera instance, float yRot, float xRot) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        Object result = VanillaHookRegistry.dispatchValue(HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET,
            new float[]{yRot, xRot});
        if (result instanceof float[]) {
            float[] adjusted = (float[]) result;
            instance.setRotation(adjusted[0], adjusted[1]);
        } else {
            instance.setRotation(yRot, xRot);
        }
    }
}
