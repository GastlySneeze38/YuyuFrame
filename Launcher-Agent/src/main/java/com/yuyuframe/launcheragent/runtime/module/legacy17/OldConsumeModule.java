package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Animation de consommation (manger/boire) à l'ancienne, 1ère personne —
 * inspiré de TAKfsg/oldblockhit-legacy-fabric (mod Fabric/Yarn, repo GitHub,
 * {@code Config.oldConsume} dans {@code HeldItemRendererMixin.java}).
 *
 * <p>Logique ici depuis le 2026-09-15 (elle vivait dans
 * {@code MixinOldConsume189}, entièrement réflexive) :
 * <ul>
 *   <li>pose de consommation remplacée ({@link HookPoint#HELD_ITEM_TRANSFORM},
 *       étape {@code "consume"}) — même courbe que la référence ;</li>
 *   <li>le swing reste visible pendant qu'on mange
 *       ({@link HookPoint#HELD_ITEM_SWING_PROGRESS}, action {@code "consume"}).</li>
 * </ul>
 */
public final class OldConsumeModule extends LauncherModule {

    private final MatrixOps ops = new MatrixOps();

    public OldConsumeModule() {
        super("old-consume", "Manger/Boire 1.7", "Animation de consommation à l'ancienne, 1ère personne", false,
            HookPoint.HELD_ITEM_TRANSFORM, HookPoint.HELD_ITEM_SWING_PROGRESS);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_TRANSFORM, this::consumePose);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_SWING_PROGRESS,
            ctx -> isEnabled() && "consume".equals(ctx) ? Boolean.TRUE : null);
    }

    /** {@code null} = pose vanilla conservée (module inactif, ou utilisation illisible). */
    private Object consumePose(Object ctx) {
        if (!isEnabled() || !Legacy17.isStage(ctx, "consume")) return null;
        int[] use = PlayerData.itemUse();
        if (use == null || use[PlayerData.USE_TOTAL] <= 0) return null;

        float useAmount = use[PlayerData.USE_REMAINING] - Legacy17.tickDelta(ctx) + 1.0f;
        float f1 = 1.0f - useAmount / use[PlayerData.USE_TOTAL];
        float f2 = 1.0f - f1;
        f2 = f2 * f2 * f2;
        f2 = f2 * f2 * f2;
        f2 = f2 * f2 * f2; // f2^27 — même "easing" que la référence Fabric (Config.oldConsume)
        float f3 = 1.0f - f2;
        float bob = Math.abs((float) Math.cos(useAmount / 4.0f * (float) Math.PI) * 0.1f) * (f1 > 0.2f ? 1f : 0f);

        return ops.clear()
            .translate(0.0f, bob, 0.0f)
            .translate(f3 * 0.6f, -f3 * 0.5f, 0.0f)
            .rotate(f3 * 90.0f, 0.0f, 1.0f, 0.0f)
            .rotate(f3 * 10.0f, 1.0f, 0.0f, 0.0f)
            .rotate(f3 * 30.0f, 0.0f, 0.0f, 1.0f);
    }
}
