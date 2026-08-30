package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.legacy17.SwingSpeedModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swing 1.7 — inspiré d'OverflowAnimations/Animatium-Legacy (repo GitHub
 * Polyfrost/OverflowAnimationsV2, {@code MixinEntityLivingBase.animatium$modifySwingSpeed},
 * qui override {@code getArmSwingAnimationEnd()} avec un facteur exponentiel).
 *
 * Bytecode RÉEL vérifié (javap sur pr.class = LivingEntity, vrai jar 1.8.9) :
 * {@code n()I} (private, method_? — indexé sous "n" dans mappings-1.8.9.tiny,
 * confirmé DIRECTEMENT sur cette classe, pas de souci d'héritage) implémente
 * EXACTEMENT {@code getArmSwingAnimationEnd()} : retourne 6 par défaut, ajusté
 * par Célérité/Fatigue quand actives. Appelée depuis DEUX endroits sur pr :
 * {@code bw()} (swingHand, décide si on peut relancer un swing) et
 * {@code bx()} (tickHandSwing, incrémente {@code as}/swingProgressInt chaque
 * tick et calcule {@code az}/handSwingProgress = as / n() — CE ratio, utilisé
 * PARTOUT pour l'animation du bras ET la courbe de position de l'item tenu en
 * 1ère personne (voir bfn.b(F,F)/HeldItemRenderer.applyEquipAndSwingOffset,
 * vérifié identique à vanilla par défaut) — donc raccourcir n() accélère TOUTE
 * l'animation de swing d'un coup, sans avoir à toucher le rendu de position
 * de l'item séparément.
 *
 * @Inject en RETURN (pas HEAD) : n() a PLUSIEURS points de retour (base/
 * Célérité/Fatigue, 3 branches distinctes dans le bytecode réel) — RETURN
 * s'injecte avant CHAQUE ireturn, donnant accès à la valeur vanilla déjà
 * calculée (donc les ajustements de potion restent intacts) via
 * {@code cir.getReturnValue()}, qu'on réduit ensuite selon le réglage de
 * vitesse avant de la renvoyer.
 */
@Mixin(targets = "net.minecraft.entity.LivingEntity")
public abstract class MixinSwingSpeed189 {

    private static long la$lastDiag;

    @Inject(method = "n()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        boolean diag = System.currentTimeMillis() - la$lastDiag > 2000;
        if (diag) la$lastDiag = System.currentTimeMillis();
        try {
            if (diag) LauncherLog.info("[MixinSwingSpeed189] la$swingSpeed appelé, this=" + this.getClass());

            LauncherModule moduleBase = ModuleRegistry.get("swing-speed-1-7");
            if (!(moduleBase instanceof SwingSpeedModule) || !moduleBase.isEnabled()) {
                if (diag) LauncherLog.info("[MixinSwingSpeed189] module null/désactivé: " + moduleBase);
                return;
            }
            SwingSpeedModule module = (SwingSpeedModule) moduleBase;

            int vanilla = cir.getReturnValue();
            int faster = Math.max(1, Math.round(vanilla * 100f / module.speedPercent));
            cir.setReturnValue(faster);
            if (diag) LauncherLog.info("[MixinSwingSpeed189] vanilla=" + vanilla + " -> faster=" + faster);
        } catch (Throwable t) {
            LauncherLog.err("[MixinSwingSpeed189] la$swingSpeed: " + t);
        }
    }
}
