package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Bascule le sprint (au lieu de le maintenir) — juste un marqueur
 * activé/désactivé ici, TOUTE la logique vit dans
 * {@code MixinToggleSprint189} (redirige {@code KeyBinding.isPressed()}
 * directement dans {@code ClientPlayerEntity.tickMovement()}, la seule
 * source de vérité vanilla pour l'état sprint — voir sa javadoc pour le
 * contexte complet, technique reprise du vrai binaire PolySprint).
 */
public final class ToggleSprintModule extends LauncherModule {
    public ToggleSprintModule() {
        super("toggle-sprint", "Toggle Sprint", "Un appui sur la touche sprint bascule l'état au lieu de le maintenir", false);
    }
}
