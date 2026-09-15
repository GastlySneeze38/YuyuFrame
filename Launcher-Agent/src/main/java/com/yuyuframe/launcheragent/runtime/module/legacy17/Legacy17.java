package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;

/**
 * Lecture du contexte de {@link HookPoint#HELD_ITEM_TRANSFORM},
 * {@code Object[]{ String étape, Float tickDelta }} — partagée par les modules
 * d'animation, qui répondent chacun à une étape.
 */
final class Legacy17 {

    private Legacy17() {
    }

    static boolean isStage(Object ctx, String stage) {
        return ctx instanceof Object[] && ((Object[]) ctx).length == 2 && stage.equals(((Object[]) ctx)[0]);
    }

    static float tickDelta(Object ctx) {
        Object v = ((Object[]) ctx)[1];
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }
}
