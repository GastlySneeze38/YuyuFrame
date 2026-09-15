package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Ralentissement de sneak PROGRESSIF au lieu d'instantané. Vérifié par
 * bytecode réel (javap sur bev.class = KeyboardInput, vrai jar 1.8.9) que le
 * ralentissement ×0.3 en sneak vanilla est appliqué INSTANTANÉMENT (pas de
 * transition à "porter" depuis la 1.7 — ce mécanisme n'existe pas tel quel
 * dans aucune version). Ceci recrée juste la SENSATION demandée (transition
 * douce), pas un portage authentique d'un comportement 1.7 réel.
 *
 * <p>Logique ici depuis le 2026-09-15 ({@link HookPoint#SNEAK_SLOWDOWN}) : le
 * facteur descend de 1 à 0,3 en {@link #rampTicks} ticks quand on s'accroupit,
 * et remonte au même rythme. Elle vivait dans {@code MixinSneakRamp189}.
 */
public final class SneakRampModule extends LauncherModule {

    public float rampTicks = 4f;

    /** Facteur courant — appelé une fois par tick d'entrées, qu'on soit accroupi ou non. */
    private float multiplier = 1.0f;

    @Override
    protected void settings(SettingList s) {
        s.slider("rampTicks", "Durée de transition (ticks)", "Réglages", 1f, 20f, 1f,
            () -> rampTicks, v -> rampTicks = v);
    }

    public SneakRampModule() {
        super("sneak-ramp-1-7", "Sneak progressif", "Ralentissement du sneak appliqué progressivement au lieu d'instantané", false,
            HookPoint.SNEAK_SLOWDOWN);
        VanillaHookRegistry.registerValue(HookPoint.SNEAK_SLOWDOWN, this::advance);
    }

    private Object advance(Object ctx) {
        if (!isEnabled()) {
            multiplier = 1.0f;
            return null;
        }
        float step = 0.7f / Math.max(1f, rampTicks); // de 1.0 (debout) à 0.3 (sneak vanilla)
        float target = Boolean.TRUE.equals(ctx) ? 0.3f : 1.0f;
        if (multiplier < target) multiplier = Math.min(target, multiplier + step);
        else if (multiplier > target) multiplier = Math.max(target, multiplier - step);
        return Double.valueOf(multiplier);
    }
}
