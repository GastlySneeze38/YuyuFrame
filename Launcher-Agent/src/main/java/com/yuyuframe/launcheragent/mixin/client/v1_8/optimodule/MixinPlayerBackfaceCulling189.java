package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Cible : net.minecraft.client.render.entity.PlayerEntityRenderer (official
 * bln), méthode a(Lbet;DDDFF)V — vérifiée par désassemblage bytecode réel :
 * cette méthode fait la vérification "invisible pour le viewer" + l'offset
 * sneak, PUIS délègue le vrai rendu du modèle à
 * LivingEntityRenderer.a(Lpr;DDDFF)V via un appel super (invokespecial #96).
 * On encadre CET appel précis (pas toute la méthode) avec
 * GlStateManager.enableCull()/disableCull() — pas org.lwjgl.opengl.GL11
 * direct, car le vanilla suit son propre état GL via GlStateManager pour
 * éviter des appels driver redondants ; appeler LWJGL directement
 * désynchroniserait ce cache interne.
 *
 * AUCUN paramètre de méthode capturé (CallbackInfo seul) — cible juste un
 * point d'injection avant/après un appel précis, pas besoin des arguments.
 */
@Mixin(targets = "net.minecraft.client.render.entity.PlayerEntityRenderer")
public abstract class MixinPlayerBackfaceCulling189 {

    private static Method enableCull;
    private static Method disableCull;
    private static boolean resolveFailed;

    @Inject(method = "a(Lbet;DDDFF)V", at = @At(value = "INVOKE", target = "Lbjl;a(Lpr;DDDFF)V"))
    private void la$enableCull(CallbackInfo ci) {
        if (!isEnabled()) return;
        try {
            Method m = enableCullMethod();
            if (m != null) m.invoke(null);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinPlayerBackfaceCulling189 enable: " + t);
        }
    }

    @Inject(method = "a(Lbet;DDDFF)V", at = @At(value = "INVOKE", target = "Lbjl;a(Lpr;DDDFF)V", shift = At.Shift.AFTER))
    private void la$disableCull(CallbackInfo ci) {
        if (!isEnabled()) return;
        try {
            Method m = disableCullMethod();
            if (m != null) m.invoke(null);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinPlayerBackfaceCulling189 disable: " + t);
        }
    }

    private static boolean isEnabled() {
        LauncherModule module = ModuleRegistry.get("player-backface-culling");
        return module != null && module.isEnabled();
    }

    private static void resolveIfNeeded() {
        if (enableCull != null || disableCull != null || resolveFailed) return;
        Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
        if (glStateManager == null) { resolveFailed = true; return; }
        enableCull = McReflect.noArgMethod(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "enableCull");
        disableCull = McReflect.noArgMethod(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "disableCull");
        if (enableCull == null || disableCull == null) resolveFailed = true;
    }

    private static Method enableCullMethod() {
        resolveIfNeeded();
        return enableCull;
    }

    private static Method disableCullMethod() {
        resolveIfNeeded();
        return disableCull;
    }
}
