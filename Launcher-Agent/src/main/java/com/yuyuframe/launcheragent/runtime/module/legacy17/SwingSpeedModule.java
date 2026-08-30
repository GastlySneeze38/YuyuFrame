package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Bascule "swing 1.7" (bras + item tenu plus rapides) — juste un marqueur
 * activé/désactivé + le réglage de vitesse ici, TOUTE la logique vit dans
 * {@code MixinSwingSpeed189} qui raccourcit
 * {@code LivingEntity.getArmSwingAnimationEnd()} (durée du swing, 6 ticks en
 * vanilla). Puisque {@code handSwingProgress} (utilisé PARTOUT pour
 * l'animation du bras ET la courbe de position de l'item tenu en 1ère
 * personne) est calculé comme {@code swingProgressInt / getArmSwingAnimationEnd()},
 * raccourcir cette seule durée accélère toute l'animation d'un coup — pas
 * besoin de toucher au rendu de position de l'item séparément (voir javadoc
 * du Mixin pour la vérification bytecode complète).
 */
public final class SwingSpeedModule extends LauncherModule {

    // Défaut aligné sur Overflow/Animatium-Legacy : leur slider "Item Swing
    // Speed" par défaut vaut 0.0 (formule exp(-0.0)=1.0, donc AUCUNE
    // accélération par défaut — juste une option à activer soi-même). 100%
    // ici = même neutralité (duration * 100/100 = durée vanilla inchangée).
    public float speedPercent = 100f;

    @Override
    protected void settings(SettingList s) {
        s.slider("speedPercent", "Vitesse du swing (%)", "Réglages", 100f, 400f, 10f,
            () -> speedPercent, v -> speedPercent = v);
    }

    public SwingSpeedModule() {
        super("swing-speed-1-7", "Swing 1.7", "Accélère l'animation de swing (bras + item tenu), façon 1.7", false);
    }
}
