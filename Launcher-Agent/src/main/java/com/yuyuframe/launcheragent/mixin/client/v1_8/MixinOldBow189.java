package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.legacy17.OldBowModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Arc 1.7 — technique additive (TAIL de {@code applyBowTransformation}, voir
 * plus bas) confirmée par DEUX mods de référence indépendants, mais valeurs
 * numériques prises chez TAKfsg/oldblockhit-legacy-fabric ({@code Config.oldBow},
 * repo GitHub, mod DÉDIÉ à Legacy Fabric 1.8.9 — donc calibré sur la position
 * de base VANILLA) plutôt que chez OverflowAnimations/Animatium-Legacy
 * ({@code animatium$lunarBowPosition}) : ces dernières sont taguées
 * "lunarPositions" dans leur propre code — calibrées pour se superposer à la
 * position de base DE LUNAR CLIENT, pas celle de vanilla, d'où un décalage
 * visuel incorrect une fois appliquées ici (retour utilisateur : "l'arc n'a
 * aucun changement" / rendu bizarre). Les valeurs TAKfsg sont une simple
 * translation, sans rotation.
 *
 * S'accroche en TAIL de {@code applyBowTransformation(F,AbstractClientPlayerEntity)V}
 * (lettre officielle "a", descripteur "(FLbet;)V", confirmé method_9863 dans
 * mappings-1.8.9.tiny) — bytecode réel vérifié : cet appel est le DERNIER de
 * la branche "arc" dans {@code renderArmHoldingItem} avant de retomber sur le
 * rendu final, donc notre ajout en TAIL devient bien la transform la plus
 * externe (pas de souci d'ordre de matrice comme rencontré sur l'épée
 * diagonale, qui elle avait d'autres appels après son point d'injection
 * initial).
 *
 * Aucun paramètre de la cible capturé (évite le bug de type strict vu sur
 * MixinOldItemRotations189 avec le paramètre {@code Lbet;}) — cette méthode
 * est appelée à chaque frame en tenant un arc, donc pas besoin de connaître
 * ses arguments pour un simple ajout de translate.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class MixinOldBow189 {

    private static long la$lastDiag;
    private static float la$capturedSwingProgress;

    // Même souci que "swing en bloquant" (voir MixinSwingWhileBlocking189) :
    // la branche ARC de renderArmHoldingItem(F)V (lettre "a") appelle ELLE
    // AUSSI applyEquipAndSwingOffset(equip, 0.0F) — un 0.0F codé en dur — au
    // lieu de la vraie swingProgress, juste avant applyBowTransformation.
    // C'est le 4ème appel (ordinal=3) à b(FF)V dans cette méthode (après
    // carte=0, outil=1, épée-bloc=2), confirmé en recomptant les 4
    // occurrences de "b(f2, 0.0F)" dans le bytecode réel de bfn.a(F)V.
    @Inject(method = "a(F)V", at = @At("HEAD"), require = 0)
    private void la$captureSwingProgress(float tickDelta, CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("old-bow");
            if (!(module instanceof OldBowModule) || !module.isEnabled()) return;
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return;
            Method getHandSwingProgress = McReflect.method(player.getClass(), "net/minecraft/entity/LivingEntity", "getHandSwingProgress", float.class);
            if (getHandSwingProgress == null) return;
            la$capturedSwingProgress = (float) getHandSwingProgress.invoke(player, tickDelta);
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldBow189] la$captureSwingProgress: " + t);
        }
    }

    @ModifyArg(method = "a(F)V", at = @At(value = "INVOKE", target = "Lbfn;b(FF)V", ordinal = 3), index = 1, require = 0)
    private float la$useRealSwingProgressWhileDrawingBow(float original) {
        try {
            LauncherModule module = ModuleRegistry.get("old-bow");
            if (module instanceof OldBowModule && module.isEnabled()) {
                return la$capturedSwingProgress;
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldBow189] la$useRealSwingProgressWhileDrawingBow: " + t);
        }
        return original;
    }

    @Inject(method = "a(FLbet;)V", at = @At("TAIL"), require = 0)
    private void la$oldBowPosition(CallbackInfo ci) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 1000;
        if (diag) la$lastDiag = System.currentTimeMillis();
        try {
            if (diag) LauncherLog.info("[MixinOldBow189] la$oldBowPosition appelé");
            LauncherModule module = ModuleRegistry.get("old-bow");
            if (!(module instanceof OldBowModule) || !module.isEnabled()) {
                if (diag) LauncherLog.info("[MixinOldBow189] module null/désactivé: " + module);
                return;
            }

            Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
            if (glStateManager == null) return;
            Method translate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "translate",
                float.class, float.class, float.class);
            if (translate == null) return;

            translate.invoke(null, 0.0f, 0.1f, -0.15f);
            if (diag) LauncherLog.info("[MixinOldBow189] transform appliqué");
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldBow189] la$oldBowPosition: " + t);
        }
    }
}
