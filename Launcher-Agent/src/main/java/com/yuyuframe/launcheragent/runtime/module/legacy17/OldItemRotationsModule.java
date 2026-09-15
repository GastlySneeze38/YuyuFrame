package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * "Old item rotations" — empêche l'item tenu en 1ère personne de suivre le
 * regard (tangage/lacet) comme en vanilla 1.8+, pour retrouver la tenue fixe
 * de 1.7. Inspiré d'OverflowAnimations/Animatium-Legacy
 * ({@code MixinItemRenderer.animatium$removeRotations}, repo GitHub
 * Polyfrost/OverflowAnimationsV2).
 *
 * <p>Répond {@link MatrixOps#EMPTY} à l'étape {@code "rotation"} de
 * {@link HookPoint#HELD_ITEM_TRANSFORM} : la pose vanilla est remplacée par
 * rien (elle était annulée par {@code MixinOldItemRotations189}).
 */
public final class OldItemRotationsModule extends LauncherModule {
    public OldItemRotationsModule() {
        super("old-item-rotations", "Item fixe (1.7)", "L'item tenu ne suit plus le regard, comme en 1.7", false,
            HookPoint.HELD_ITEM_TRANSFORM);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_TRANSFORM,
            ctx -> isEnabled() && Legacy17.isStage(ctx, "rotation") ? MatrixOps.EMPTY : null);
    }
}
