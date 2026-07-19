package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Portage 1.21.11 de {@code CameraFreelookMixin261} — architecture
 * DIFFÉRENTE du bracket 26.1.2, vérifiée par désassemblage complet (javap,
 * pas seulement un scan linéaire) de {@code Camera.update(World,Entity,
 * boolean,boolean,float)V} (obf {@code ger.a}, vrai jar 1.21.11) :
 *
 * <p>Contrairement à 26.1.2 (méthode séparée {@code alignWithEntity(F)V}
 * appelée par {@code update()}), 1.21.11 fait TOUT dans {@code update()}
 * lui-même — aucune méthode séparée equivalente à {@code alignWithEntity}.
 * Structure confirmée par lecture d'instructions (offsets bytecode) :
 * <pre>
 *   branche "sommeil" (entity.isSleeping() + dans un lit) : setRotation()
 *     PUIS setPos(Vec3) — jamais de moveBy, jamais atteinte hors sommeil.
 *   branche normale : setRotation(entity.getViewYRot/XRot au tickProgress)
 *     PUIS setPos(DDD) interpolé — c'est la rotation/position DE BASE,
 *     TOUJOURS exécutée (première personne comprise).
 *   SI thirdPerson :
 *     SI inverseView (vue de face) : re-setRotation(yaw+180, -pitch).
 *     PUIS moveBy(-clipDistance, 0, 0) — recul de la caméra (3e personne).
 *   SINON (thirdPerson=false) :
 *     SI l'entité a une capacité de vue rapprochée (ex: longue-vue) :
 *       setRotation(...) PUIS moveBy(0, 0.3f, 0) — petit décalage avant.
 *     SINON : rien de plus — 1re personne pure, la rotation DE BASE ci-
 *       dessus reste la rotation finale de la caméra, JAMAIS de moveBy.
 * </pre>
 *
 * Ceci généralise le problème résolu sur 26.1.2 ("la caméra tourne autour
 * d'elle-même" — voir {@code CameraFreelookMixin261}) : {@code moveBy}
 * recule/avance la caméra le long de SA ROTATION COURANTE (lit le
 * quaternion déjà posé par le dernier {@code setRotation}), donc appliquer
 * l'offset de freelook APRÈS moveBy laisserait la position calée sur
 * l'ancienne direction. Ici il y a DEUX call sites de {@code moveBy(FFF)V}
 * dans {@code update()} (3e personne ET vue rapprochée) au lieu d'un seul —
 * plutôt que cibler chaque site d'appel individuellement (@At INVOKE, format
 * de clé refmap non couvert par {@code LauncherMixinService.buildRefmapJson()}
 * pour ce cas), ce Mixin hook DIRECTEMENT {@code moveBy(FFF)V} lui-même (HEAD) :
 * un seul point d'accroche couvre les deux branches automatiquement, puisque
 * {@code moveBy} lit le quaternion de rotation AU MOMENT DE SON PROPRE appel
 * pour tourner le vecteur de déplacement — appliquer l'offset juste avant
 * (dans son propre corps) produit exactement le même effet qu'un hook avant
 * chaque site d'appel, sans dépendre du nombre de sites.
 *
 * {@code la$moveCalledThisUpdate} (champ d'instance, remis à faux en HEAD de
 * {@code update()}) évite le double-comptage de l'offset : si {@code moveBy}
 * a tourné cette frame (3e personne ou vue rapprochée), le hook TAIL de
 * {@code update()} ne réapplique PAS l'offset (déjà fait dans moveBy) — sinon
 * (1re personne pure, jamais de moveBy) le hook TAIL l'applique lui-même,
 * seul point où la rotation de base n'est jamais retouchée après coup.
 *
 * BUG TROUVÉ (retour utilisateur : "fait en sorte que ça fasse la même
 * mécanique que vanilla pour s'adapter à la collision avec la cam et
 * prendre une distance en s'adaptant avec les murs") : la caméra traversait
 * les murs pendant le freelook en 3e personne. Cause trouvée par
 * désassemblage complet (javap) du vrai jar 1.21.11 de {@code update()} :
 * dans la branche 3e personne, l'ordre RÉEL des appels est {@code
 * setRotation(FF)} PUIS {@code clipToSpace(F)F} (raycast de collision réel,
 * équivalent Yarn de {@code getMaxZoom} sur 26.1.2/{@code ger.a(F)F} obf)
 * PUIS {@code moveBy(-clipToSpace(...), 0, 0)}. Le hook {@code la$beforeMoveBy}
 * (avant cette correction) appliquait l'offset de freelook au TOUT DÉBUT de
 * {@code moveBy}, donc APRÈS que {@code clipToSpace} ait déjà fait son
 * raycast avec l'ANCIENNE rotation (avant offset) — la distance de recul ne
 * correspondait jamais à la VRAIE direction de vue du freelook.
 *
 * Corrigé : nouveau hook {@code la$beforeClipToSpace}, injecté juste AVANT
 * l'appel à {@code clipToSpace(F)F} DANS {@code update()} (confirmé unique
 * par désassemblage — un seul site d'appel dans toute la méthode, propre à
 * la branche 3e personne). Comme {@code setRotation} met à jour les champs
 * dérivés lus par {@code clipToSpace} pour son raycast, le recul est
 * désormais calculé EXACTEMENT comme vanilla le ferait pour cette direction
 * de vue. {@code la$offsetAppliedForMove} (remis à faux en HEAD de {@code
 * update()}, comme {@code la$moveCalledThisUpdate}) évite que {@code
 * la$beforeMoveBy} ne réapplique l'offset UNE SECONDE FOIS pour CE MÊME
 * appel à {@code moveBy} (déjà fait avant {@code clipToSpace} quelques
 * instructions plus tôt) — la branche "vue rapprochée" (spyglass, second
 * site d'appel de {@code moveBy}, SANS {@code clipToSpace} avant, offset
 * fixe 0.3f) continue de recevoir l'offset via {@code la$beforeMoveBy}
 * normalement, ce flag restant à faux pour elle.
 */
@Mixin(targets = "net.minecraft.client.render.Camera")
public abstract class CameraFreelookMixin {

    private static volatile Field fPitch, fYaw;
    private static volatile Method mSetRotation;

    private boolean la$moveCalledThisUpdate;
    private boolean la$offsetAppliedForMove;

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("HEAD"), require = 0)
    private void la$onUpdateHead(CallbackInfo ci) {
        la$moveCalledThisUpdate = false;
        la$offsetAppliedForMove = false;
    }

    @Inject(method = "clipToSpace(F)F", at = @At("HEAD"), require = 0)
    private void la$beforeClipToSpace(CallbackInfo ci) {
        try {
            if (!FreelookModule.isActive()) return;
            applyOffset();
            la$offsetAppliedForMove = true;
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin] la$beforeClipToSpace: " + t);
        }
    }

    @Inject(method = "moveBy(FFF)V", at = @At("HEAD"), require = 0)
    private void la$beforeMoveBy(CallbackInfo ci) {
        try {
            la$moveCalledThisUpdate = true;
            if (!FreelookModule.isActive()) return;
            if (la$offsetAppliedForMove) return;
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin] la$beforeMoveBy: " + t);
        }
    }

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL"), require = 0)
    private void la$onUpdateTail(CallbackInfo ci) {
        try {
            if (!FreelookModule.isActive()) return;
            if (la$moveCalledThisUpdate) return;
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin] la$onUpdateTail: " + t);
        }
    }

    private void applyOffset() throws Exception {
        if (fPitch == null) {
            fPitch = McReflect.field(this.getClass(), "net/minecraft/client/render/Camera", "pitch");
            fYaw = McReflect.field(this.getClass(), "net/minecraft/client/render/Camera", "yaw");
            mSetRotation = McReflect.method(this.getClass(), "net/minecraft/client/render/Camera",
                "setRotation", float.class, float.class);
            if (fPitch == null || fYaw == null || mSetRotation == null) return;
        }
        float pitch = fPitch.getFloat(this);
        float yaw = fYaw.getFloat(this);
        float newPitch = clamp(pitch + (float) FreelookModule.pitchOffset(), -90f, 90f);
        float newYaw = yaw + (float) FreelookModule.yawOffset();
        // setRotation(FF)V : p1=yaw, p2=pitch — confirmé via mappings/mappings.tiny
        // (Camera.setRotation, paramètres nommés "yaw" puis "pitch" dans cet ordre).
        mSetRotation.invoke(this, newYaw, newPitch);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
