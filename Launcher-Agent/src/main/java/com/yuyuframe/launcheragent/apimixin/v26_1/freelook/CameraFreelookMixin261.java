package com.yuyuframe.launcheragent.apimixin.v26_1.freelook;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Équivalent apimixin de l'ancien {@code mixin.client.v26_1.CameraFreelookMixin261}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet des 3 bugs trouvés — ordre
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
 * ⚠️ ÉTAT ACTUEL : {@code @WrapOperation} (MixinExtras), 2026-08-25 §14 —
 * après {@code @ModifyArgs} puis {@code @Redirect}. Ne pas « simplifier » sans
 * lire les deux paragraphes ci-dessous.
 *
 * Choisi pour la COMPOSABILITÉ : deux {@code @Redirect} concurrents sur un même
 * site d'appel s'excluent, alors que plusieurs {@code @WrapOperation}
 * s'empilent. {@code freecam} est installé chez l'utilisateur et a toutes les
 * raisons de toucher {@code Camera}. C'est aussi l'approche d'Omnilook.
 * Redevenu possible seulement une fois MixinExtras réellement initialisé dans
 * NOTRE environnement Mixin — jusque-là toute annotation MixinExtras
 * d'{@code apimixin/} était silencieusement inerte, voir
 * {@code IsolatedBootstrap.initMixinExtras()}.
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
 * ⚠️ {@code ordinal = 1} OBLIGATOIRE (2026-08-25, §15 — retour utilisateur :
 * "en vue arrière on ne peut pas aller vers le haut"). {@code alignWithEntity}
 * contient QUATRE appels à {@code setRotation(FF)}, vérifiés par javap du vrai
 * jar 26.1.2, dans cet ordre bytecode :
 * <pre>
 *   #0 (véhicule/minecart, lerp)     — hors sujet ici
 *   #1 (branche normale) : setRotation(entity.getViewYRot, entity.getViewXRot)
 *   #2 (SEULEMENT si isMirrored(), donc SEULEMENT en THIRD_PERSON_FRONT) :
 *      setRotation(this.yRot + 180, -this.xRot) — retourne yaw/pitch pour
 *      que le joueur se voie à l'endroit depuis une caméra qui lui fait face
 *   #3 (sommeil) — hors sujet ici
 * </pre>
 * Sans {@code ordinal}, {@code @At(INVOKE)} matche les QUATRE — en vue Arrière
 * (jamais mirrored) seul #1 s'exécute réellement, sans conséquence. En vue
 * AVANT, #1 ET #2 s'exécutent tous les deux DANS LA MÊME frame : notre offset
 * s'appliquait donc deux fois — une fois sur #1, une seconde fois sur #2, qui
 * relit un xRot/yRot DÉJÀ modifié par nous. {@code ordinal = 1} nous limite à
 * #1 et laisse le retournement vanilla de #2 tourner INTACT sur le résultat.
 *
 * C'est ce qui règle le blocage vertical en vue Arrière (racine vanilla, pas
 * un bug introduit ici) : {@code getMaxZoom()}/{@code move()}, juste après,
 * font un rayon "derrière" la rotation ACTUELLE de la caméra pour savoir
 * jusqu'où reculer sans traverser un mur. En Arrière, ce rayon part
 * directement du pitch qu'on vient de fixer — près de -90° (regarder tout en
 * haut), "derrière la vue" pointe vers le SOL, le rayon touche presque
 * aussitôt, {@code maxZoom} s'effondre vers 0 et la caméra se plaque contre
 * le joueur (comportement vanilla natif du F5 arrière, retrouvable sans
 * freelook). En Avant, l'appel #2 (désormais intact) NÉGATIVE le pitch avant
 * ce même calcul — le rayon part dans la direction opposée et ne s'effondre
 * pas de la même façon, exactement comme le F5 avant vanilla s'en sort déjà.
 * {@link FreelookModule#thirdPersonView} par défaut sur "Avant" pour cette
 * raison.
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

    @WrapOperation(method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1))
    private void la$applyFreelookOffset(Camera instance, float yRot, float xRot, Operation<Void> setRotation) {
        FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
        Object result = VanillaHookRegistry.dispatchValue(HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET,
            new float[]{yRot, xRot});
        if (result instanceof float[]) {
            float[] adjusted = (float[]) result;
            setRotation.call(instance, adjusted[0], adjusted[1]);
        } else {
            setRotation.call(instance, yRot, xRot);
        }
    }
}
