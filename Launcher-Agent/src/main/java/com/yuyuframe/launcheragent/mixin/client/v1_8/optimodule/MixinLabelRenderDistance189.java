package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.optimodule.LabelRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Cible : net.minecraft.client.render.entity.EntityRenderer (official biv),
 * méthode renderLabelIfPresent(T,String,DDDI)V (officiel "a", method_6917).
 *
 * CORRECTIF IMPORTANT #1 (trouvé en test réel, pas en théorie) : la toute
 * première version de ce fichier capturait le 1er paramètre (l'entité,
 * générique T effacé en {@code pk}) en {@code Object}. En conditions
 * réelles, log Mixin à l'appui, CE PATTERN NE MARCHE PAS DANS CE BOOTSTRAP
 * CUSTOM dès qu'il y a des PRIMITIVES après le paramètre référence dans la
 * signature capturée : {@code InvalidInjectionException: Expected
 * (Lpk;Ljava/lang/String;DDDI...)V but found
 * (Ljava/lang/Object;Ljava/lang/String;DDDI...)V} — Mixin rejette TOUT le
 * Mixin dès qu'UN SEUL de ses handlers a un descripteur invalide. Corrigé en
 * capturant x/y/z par @ModifyVariable(index=slot exact) au lieu de capturer
 * le paramètre entité — confirmé fonctionnel en jeu (log : plus aucune
 * InvalidInjectionException sur cette classe pour la coupure de distance et
 * le skip de rotation billboard).
 *
 * CORRECTIF IMPORTANT #2 (trouvé APRÈS le correctif #1, toujours en test
 * réel) : les redirects visant des méthodes D'INSTANCE de classes obfusquées
 * du package par défaut (TextRenderer=avn, BufferBuilder=bfd,
 * Tessellator=bfx) échouaient ENCORE, cette fois à cause du paramètre
 * RECEIVER (pas un paramètre capturé de la méthode englobante) : {@code
 * InvalidInjectionException: @Redirect handler method biv::la$cachedStringWidth
 * has an invalid signature. Found unexpected argument type java.lang.Object
 * at index 0, expected avn.} Un @Redirect sur un appel de méthode D'INSTANCE
 * a TOUJOURS besoin d'un receiver exactement typé (1er paramètre du
 * handler) — et comme ces classes vivent dans le package par défaut, JAMAIS
 * possible de les typer depuis une classe Mixin dans un package nommé (même
 * limitation Java que pour les stubs, voir historique du projet). Ce
 * bootstrap n'a pas de remapping bytecode complet (contrairement à un vrai
 * Fabric Loom) donc aucun contournement possible ici.
 *
 * CONSÉQUENCE : le cache de largeur de texte (ex-item 2), le carré de fond
 * semi-transparent (ex-item 3) et la passe "à travers les murs" (ex-item 4)
 * ont été RETIRÉS de cette classe — impossibles à faire via @Redirect ici.
 * Le cache de largeur de texte a été déplacé dans
 * {@link MixinCachedStringWidth189}, qui mixe directement DANS TextRenderer
 * (sur la méthode elle-même, pas un call-site) : aucun receiver à typer,
 * donc aucun problème. Le carré de fond et la passe "à travers les murs"
 * restent non implémentés — aucune solution trouvée compatible avec ce
 * bootstrap sans passer TOUTE la classe Mixin dans le package par défaut
 * (contraire aux conventions du projet).
 *
 * Ce qui reste ACTIF et confirmé fonctionnel ici : coupure totale de
 * distance (maxDistance) + skip de la rotation billboard face caméra au-delà
 * de simplifyDistance (redirect sur GlStateManager.rotate, méthode STATIQUE
 * donc sans receiver à typer — safe).
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderer")
public abstract class MixinLabelRenderDistance189 {

    // ── Capture x/y/z par slot de variable locale (jamais le paramètre entité) ──

    private static double la$curX;
    private static double la$curY;
    private static double la$curZ;

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 3)
    private double la$captureX(double x) { la$curX = x; return x; }

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 5)
    private double la$captureY(double y) { la$curY = y; return y; }

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 7)
    private double la$captureZ(double z) { la$curZ = z; return z; }

    // ── Coupure totale (au-delà de maxDistance) ─────────────────────────────

    @Inject(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), cancellable = true)
    private void la$cullDistantLabels(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("label-render-distance");
            if (module == null || !module.isEnabled() || !(module instanceof LabelRenderDistanceModule)) return;
            float maxDist = ((LabelRenderDistanceModule) module).maxDistance;
            if (la$dist2() > (double) (maxDist * maxDist)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 cull: " + t);
        }
    }

    // ── Pas de rotation "billboard" face caméra au-delà de la distance de simplification ──
    // Cible : GlStateManager.rotate(FFFF)V (official bfl.b) — méthode STATIQUE,
    // donc le redirect n'a pas de receiver à typer (safe dans ce bootstrap).

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfl;b(FFFF)V"))
    private void la$skipBillboardRotate(float angle, float rx, float ry, float rz) {
        try {
            if (la$isBeyondSimplifyDistance()) return;
            Method rotate = la$rotateMethod();
            if (rotate != null) rotate.invoke(null, angle, rx, ry, rz);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 rotate: " + t);
        }
    }

    // ── Distance partagée (calculée depuis les champs capturés par @ModifyVariable) ──

    private static double la$dist2() {
        return la$curX * la$curX + la$curY * la$curY + la$curZ * la$curZ;
    }

    private static boolean la$isBeyondSimplifyDistance() {
        LauncherModule module = ModuleRegistry.get("label-render-distance");
        if (module == null || !module.isEnabled() || !(module instanceof LabelRenderDistanceModule)) return false;
        float simplifyDist = ((LabelRenderDistanceModule) module).simplifyDistance;
        return la$dist2() > (double) (simplifyDist * simplifyDist);
    }

    // ── Résolution réflexive partagée ────────────────────────────────────────

    private static Method la$rotate;
    private static boolean la$resolveFailed;

    private static Method la$rotateMethod() { la$resolveGl(); return la$rotate; }

    private static void la$resolveGl() {
        if (la$rotate != null || la$resolveFailed) return;
        try {
            Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
            if (glStateManager == null) {
                la$resolveFailed = true;
                return;
            }
            la$rotate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "rotate",
                float.class, float.class, float.class, float.class);
            if (la$rotate == null) {
                la$resolveFailed = true;
            }
        } catch (Throwable t) {
            la$resolveFailed = true;
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 resolveGl: " + t);
        }
    }
}
