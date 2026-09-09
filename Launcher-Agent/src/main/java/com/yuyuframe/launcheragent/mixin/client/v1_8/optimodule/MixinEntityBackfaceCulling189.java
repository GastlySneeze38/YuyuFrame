package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Cible : net.minecraft.client.render.entity.LivingEntityRenderer (official
 * bjl), méthode a(Lpr;DDDFF)V — le doRender générique partagé par TOUTES les
 * entités vivantes (zombies, vaches, squelettes... et joueurs, qui en
 * héritent via PlayerEntityRenderer.bln, voir MixinPlayerBackfaceCulling189
 * pour le toggle joueur séparé et plus ciblé). Encadre la méthode ENTIÈRE
 * (HEAD/RETURN, pas un appel précis comme le toggle joueur) puisqu'ici c'est
 * directement la classe qui fait le vrai rendu du modèle.
 *
 * Aucun paramètre capturé — juste besoin d'un point d'entrée/sortie, pas des
 * arguments.
 */
@Mixin(targets = "net.minecraft.client.render.entity.LivingEntityRenderer")
public abstract class MixinEntityBackfaceCulling189 {

    private static Method enableCull;
    private static Method disableCull;
    private static boolean resolveFailed;

    @Inject(method = "a(Lpr;DDDFF)V", at = @At("HEAD"))
    private void la$enableCull(CallbackInfo ci) {
        if (!isEnabled()) return;
        try {
            Method m = enableCullMethod();
            if (m != null) m.invoke(null);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinEntityBackfaceCulling189 enable: " + t);
        }
    }

    @Inject(method = "a(Lpr;DDDFF)V", at = @At("RETURN"))
    private void la$disableCull(CallbackInfo ci) {
        if (!isEnabled()) return;
        try {
            Method m = disableCullMethod();
            if (m != null) m.invoke(null);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinEntityBackfaceCulling189 disable: " + t);
        }
    }

    private static boolean isEnabled() {
        LauncherModule module = ModuleRegistry.get("entity-backface-culling");
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
