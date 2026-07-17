package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Voir {@code FreelookModule} pour l'architecture complète.
 *
 * BUG TROUVÉ #1 (retour utilisateur : "tout les contrôles sont inversés" +
 * caméra à l'envers) : {@code setRotation(F,F)} prend {@code (yRot, xRot)}
 * DANS CET ORDRE, PAS {@code (xRot, yRot)} comme le laisserait supposer
 * l'ordre de déclaration des champs — confirmé en retraçant l'ordre RÉEL des
 * appels dans {@code alignWithEntity} (bytecode du vrai jar 26.1.2) :
 * {@code entity.getViewYRot(F)} est évalué EN PREMIER (donc 1er argument),
 * {@code entity.getViewXRot(F)} en second — {@code setRotation(yRot, xRot)}.
 * Corrigé (voir les deux hooks ci-dessous, tous deux appellent
 * {@code setRotation(newYRot, newXRot)} dans ce sens).
 *
 * BUG TROUVÉ #2 (retour utilisateur : "la caméra tourne autour d'elle-même,
 * pas autour du joueur") : en vue à la 3e personne, {@code alignWithEntity}
 * pose D'ABORD la position caméra sur l'œil du joueur (setPosition), PUIS
 * la rotation (setRotation), PUIS — SEULEMENT si détachée (pas 1re
 * personne, {@code this.detached}, confirmé bytecode) — recule la caméra le
 * long de sa rotation COURANTE via {@code move(0,0,-distance)} (le recul
 * utilise donc la rotation déjà posée à CET instant). Un premier jet
 * appliquait l'offset de freelook seulement à la TAIL de
 * {@code update(DeltaTracker)V} — donc APRÈS que {@code move()} ait déjà
 * reculé la caméra le long de l'ANCIENNE rotation : la position restait
 * calée sur l'ancienne direction, seule la rotation changeait ensuite —
 * exactement "tourner autour de soi-même" plutôt qu'autour du joueur.
 *
 * Fix : {@code la$beforeMove} s'injecte juste AVANT l'appel interne à
 * {@code move(FFF)V} DANS {@code alignWithEntity} — applique l'offset de
 * freelook à la rotation À CET INSTANT PRÉCIS, donc {@code move()} recule
 * la caméra le long de la NOUVELLE direction (freelook incluse) : la caméra
 * orbite correctement autour du point d'ancrage (l'œil du joueur), jamais
 * sur elle-même. Ce hook ne se déclenche QUE si {@code move()} est
 * effectivement appelé, c'est-à-dire QUE en vue détachée (3e personne) —
 * {@code la$onUpdateTail} reste nécessaire pour la 1re personne (où
 * {@code move()} n'est jamais appelé, la position = l'œil directement, la
 * rotation seule suffit) ; gardé par {@code !detached} pour ne jamais
 * appliquer l'offset DEUX FOIS en 3e personne (une fois dans chaque hook).
 */
@Mixin(targets = "net.minecraft.client.Camera")
public abstract class CameraFreelookMixin261 {

    private static Field fXRot, fYRot, fDetached;
    private static Method mSetRotation;

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void la$onUpdateTail(CallbackInfo ci) {
        try {
            if (!FreelookModule.isActive()) return;
            ensureResolved();
            // Vue détachée (3e personne) : déjà géré par la$beforeMove
            // (avant le recul de la caméra) — voir javadoc de classe, pour
            // ne jamais appliquer l'offset deux fois.
            if (fDetached.getBoolean(this)) return;
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin261] la$onUpdateTail: " + t);
        }
    }

    @Inject(method = "alignWithEntity(F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V"))
    private void la$beforeMove(CallbackInfo ci) {
        try {
            if (!FreelookModule.isActive()) return;
            ensureResolved();
            applyOffset();
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin261] la$beforeMove: " + t);
        }
    }

    private void ensureResolved() throws Exception {
        if (fXRot != null) return;
        fXRot = this.getClass().getDeclaredField("xRot");
        fXRot.setAccessible(true);
        fYRot = this.getClass().getDeclaredField("yRot");
        fYRot.setAccessible(true);
        fDetached = this.getClass().getDeclaredField("detached");
        fDetached.setAccessible(true);
        mSetRotation = this.getClass().getDeclaredMethod("setRotation", float.class, float.class);
        mSetRotation.setAccessible(true);
    }

    private void applyOffset() throws Exception {
        float xRot = fXRot.getFloat(this);
        float yRot = fYRot.getFloat(this);
        float newXRot = clamp(xRot + (float) FreelookModule.pitchOffset(), -90f, 90f);
        float newYRot = yRot + (float) FreelookModule.yawOffset();
        // setRotation(yRot, xRot) — voir BUG TROUVÉ #1 dans la javadoc de classe.
        mSetRotation.invoke(this, newYRot, newXRot);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
