package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.optimodule.CachedFancyCloudsModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Cible : net.minecraft.client.render.WorldRenderer (official bfr), méthode
 * privée renderFancyClouds(FI)V (officiel "c", method_9913) — vérifiée par
 * désassemblage bytecode réel : régénère toute la géométrie des nuages
 * (grille 3D de cubes) via Tessellator, à chaque appel, sans aucune mise en
 * cache (1499 lignes de bytecode). AUCUN paramètre de la cible capturé — pas
 * besoin, on encadre juste tout le corps de la méthode.
 *
 * Technique : display list OpenGL, appelée par réflexion (org.lwjgl.opengl.GL11
 * n'est PAS disponible à la compilation — ce projet compile contre des stubs,
 * pas le vrai jar LWJGL, voir UiRenderer.gl()/McReflect.rawClass pour le même
 * besoin ailleurs dans ce projet). Au moment de reconstruire (rarement, au
 * lieu de chaque frame), on laisse la méthode d'origine s'exécuter mais
 * capturée dans une display list (GL_COMPILE_AND_EXECUTE = 4864 — dessine ET
 * enregistre en même temps, donc la frame de reconstruction reste identique
 * au vanilla) ; les autres frames, on rejoue juste la liste (glCallList) et
 * on annule l'exécution réelle de la méthode (ci.cancel()).
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class MixinCachedFancyClouds189 {

    private static final int GL_COMPILE_AND_EXECUTE = 4864;

    private static int la$listId = -1;
    private static long la$lastBuildTime;
    private static boolean la$capturing;

    private static Method la$glGenLists;
    private static Method la$glNewList;
    private static Method la$glEndList;
    private static Method la$glCallList;
    private static boolean la$resolveFailed;

    @Inject(method = "c(FI)V", at = @At("HEAD"), cancellable = true)
    private void la$cloudCacheHead(float partialTicks, int pass, CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("cached-fancy-clouds");
            if (module == null || !module.isEnabled() || !(module instanceof CachedFancyCloudsModule)) return;
            if (!la$resolveGl()) return;
            long intervalMs = Math.round(((CachedFancyCloudsModule) module).rebuildIntervalMs);
            long now = System.currentTimeMillis();

            if (la$listId == -1 || now - la$lastBuildTime > intervalMs) {
                if (la$listId == -1) la$listId = (int) la$glGenLists.invoke(null, 1);
                la$glNewList.invoke(null, la$listId, GL_COMPILE_AND_EXECUTE);
                la$capturing = true;
                la$lastBuildTime = now;
                // Ne PAS annuler ici — on laisse la méthode d'origine tourner
                // normalement, ses appels GL sont capturés dans la liste par
                // GL_COMPILE_AND_EXECUTE (voir la$cloudCacheReturn pour la fermeture).
            } else {
                la$glCallList.invoke(null, la$listId);
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinCachedFancyClouds189 head: " + t);
        }
    }

    @Inject(method = "c(FI)V", at = @At("RETURN"))
    private void la$cloudCacheReturn(CallbackInfo ci) {
        if (!la$capturing) return;
        try {
            la$glEndList.invoke(null);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinCachedFancyClouds189 return: " + t);
        } finally {
            la$capturing = false;
        }
    }

    private static boolean la$resolveGl() {
        if (la$glGenLists != null) return true;
        if (la$resolveFailed) return false;
        Class<?> gl11 = McReflect.rawClass("org.lwjgl.opengl.GL11");
        if (gl11 == null) { la$resolveFailed = true; return false; }
        la$glGenLists = McReflect.rawMethod(gl11, "glGenLists", int.class);
        la$glNewList = McReflect.rawMethod(gl11, "glNewList", int.class, int.class);
        la$glEndList = McReflect.rawMethod(gl11, "glEndList");
        la$glCallList = McReflect.rawMethod(gl11, "glCallList", int.class);
        if (la$glGenLists == null || la$glNewList == null || la$glEndList == null || la$glCallList == null) {
            la$resolveFailed = true;
            return false;
        }
        return true;
    }
}
