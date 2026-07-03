package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Position d'arc à l'ancienne (1ère personne) — inspiré d'OverflowAnimations/
 * Animatium-Legacy ({@code animatium$lunarBowPosition}, repo GitHub
 * Polyfrost/OverflowAnimationsV2). Juste un marqueur ici, toute la logique
 * vit dans {@code MixinOldBow189}.
 */
public final class OldBowModule extends LauncherModule {
    public OldBowModule() {
        super("old-bow", "Arc 1.7", "Position de l'arc en 1ère personne façon 1.7", false);
    }
}
