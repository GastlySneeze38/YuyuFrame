package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Position d'arc à l'ancienne (1ère personne) — inspiré d'OverflowAnimations/
 * Animatium-Legacy ({@code animatium$lunarBowPosition}, repo GitHub
 * Polyfrost/OverflowAnimationsV2).
 *
 * <p>Deux effets, logique ici depuis le 2026-09-15 (elle vivait dans
 * {@code MixinOldBow189}) :
 * <ul>
 *   <li>arc remonté et avancé une fois bandé
 *       ({@link HookPoint#HELD_ITEM_TRANSFORM}, étape {@code "bow"}) ;</li>
 *   <li>le swing reste visible pendant qu'on bande
 *       ({@link HookPoint#HELD_ITEM_SWING_PROGRESS}, action {@code "bow"}).</li>
 * </ul>
 */
public final class OldBowModule extends LauncherModule {

    /** Toujours la même translation : une seule instance, jamais modifiée après construction. */
    private final MatrixOps bowOffset = new MatrixOps().translate(0f, 0.1f, -0.15f);

    public OldBowModule() {
        super("old-bow", "Arc 1.7", "Position de l'arc en 1ère personne façon 1.7", false,
            HookPoint.HELD_ITEM_TRANSFORM, HookPoint.HELD_ITEM_SWING_PROGRESS);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_TRANSFORM,
            ctx -> isEnabled() && Legacy17.isStage(ctx, "bow") ? bowOffset : null);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_SWING_PROGRESS,
            ctx -> isEnabled() && "bow".equals(ctx) ? Boolean.TRUE : null);
    }
}
