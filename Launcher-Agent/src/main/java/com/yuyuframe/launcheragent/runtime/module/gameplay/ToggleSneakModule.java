package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Bascule le sneak (au lieu de le maintenir) — juste un marqueur
 * activé/désactivé ici, TOUTE la logique vit dans
 * {@code MixinToggleSneak189} (redirige {@code KeyBinding.isPressed()}
 * directement dans {@code KeyboardInput.tick()}, la seule source de vérité
 * vanilla pour l'état sneak à chaque tick — voir sa javadoc pour le contexte
 * complet, technique reprise du vrai binaire PolySprint).
 */
public final class ToggleSneakModule extends LauncherModule {
    public ToggleSneakModule() {
        super("toggle-sneak", "Toggle Sneak", "Un appui sur la touche sneak bascule l'état au lieu de le maintenir", false);
    }
}
