package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * "Old item rotations" — empêche l'item tenu en 1ère personne de suivre le
 * regard (tangage/lacet) comme en vanilla 1.8+, pour retrouver la tenue fixe
 * de 1.7. Inspiré d'OverflowAnimations/Animatium-Legacy
 * ({@code MixinItemRenderer.animatium$removeRotations}, repo GitHub
 * Polyfrost/OverflowAnimationsV2) — juste un marqueur ici, toute la logique
 * vit dans {@code MixinOldItemRotations189}.
 */
public final class OldItemRotationsModule extends LauncherModule {
    public OldItemRotationsModule() {
        super("old-item-rotations", "Item fixe (1.7)", "L'item tenu ne suit plus le regard, comme en 1.7", false);
    }
}
