package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Divise par 2 la fréquence de mise à jour des textures animées (eau, lave,
 * feu, portails...) — voir {@code SpriteAtlasTexture.update()}, appelée
 * normalement à chaque tick client pour avancer chaque sprite animé d'une
 * frame. Une frame sur deux est ignorée quand actif : les animations restent
 * fluides à l'œil (rarement perceptible), mais deux fois moins de travail de
 * recomposition d'atlas de texture. Même principe que "Low Animation Tick"
 * de PolyPatcher (1000 → 500 mises à jour/s dans leur changelog).
 */
public final class LowAnimationTickModule extends LauncherModule {

    public LowAnimationTickModule() {
        super("low-animation-tick", "Animations réduites", "Divise par 2 la fréquence des textures animées (eau, lave, feu...)", true);
    }
}
