package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cible : net.minecraft.client.render.entity.ItemEntityRenderer (official bjf),
 * méthode privée sans nom Yarn (method_10221) — descriptor (Luz;DDDFLboq;)I,
 * qui calcule le nombre de copies du modèle à dessiner pour une entité item au
 * sol selon la taille de son stack (vérifié par désassemblage bytecode réel :
 * la boucle de rendu dans render() utilise directement cette valeur comme
 * borne). AUCUN paramètre de la cible capturé (voir historique du projet —
 * capturer un paramètre de type référence non-primitif ici casserait
 * l'injection dans ce bootstrap Mixin custom) : on plafonne juste la valeur
 * de retour à 1 en RETURN, seul point où la valeur calculée existe.
 */
@Mixin(targets = "net.minecraft.client.render.entity.ItemEntityRenderer")
public abstract class MixinUnstackedItems189 {

    @Inject(method = "a(Luz;DDDFLboq;)I", at = @At("RETURN"), cancellable = true)
    private void la$clampToOneCopy(CallbackInfoReturnable<Integer> cir) {
        try {
            LauncherModule module = ModuleRegistry.get("unstacked-items");
            if (module != null && module.isEnabled() && cir.getReturnValueI() > 1) {
                cir.setReturnValue(1);
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinUnstackedItems189: " + t);
        }
    }
}
