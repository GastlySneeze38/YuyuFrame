package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Bascule "swing 1.7" (bras + item tenu plus rapides) — raccourcit la durée du
 * swing ({@link HookPoint#SWING_DURATION}, 6 ticks en vanilla). L'avancement du
 * swing, qui pilote À LA FOIS le bras et la courbe de l'objet tenu en 1ère
 * personne, est calculé comme {@code ticks / durée} : raccourcir cette seule
 * durée accélère toute l'animation d'un coup.
 *
 * <p>Logique ici depuis le 2026-09-15 (elle vivait dans
 * {@code MixinSwingSpeed189}).
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
        super("swing-speed-1-7", "Swing 1.7", "Accélère l'animation de swing (bras + item tenu), façon 1.7", false,
            HookPoint.SWING_DURATION);
        VanillaHookRegistry.registerValue(HookPoint.SWING_DURATION, this::duration);
    }

    private Object duration(Object ctx) {
        if (!isEnabled() || !(ctx instanceof Integer) || speedPercent <= 0f) return null;
        return Integer.valueOf(Math.max(1, Math.round((Integer) ctx * 100f / speedPercent)));
    }
}
