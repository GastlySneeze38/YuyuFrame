package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Ralentissement de sneak PROGRESSIF au lieu d'instantané. Marqueur pur, toute
 * la logique vit dans {@code MixinSneakRamp189} — voir sa javadoc pour le
 * contexte : vérifié par bytecode réel (javap sur bev.class = KeyboardInput,
 * vrai jar 1.8.9) que le ralentissement ×0.3 en sneak vanilla est appliqué
 * INSTANTANÉMENT (pas de transition à "porter" depuis la 1.7 — ce mécanisme
 * n'existe pas tel quel dans aucune version). Ceci recrée juste la SENSATION
 * demandée (transition douce), pas un portage authentique d'un comportement
 * 1.7 réel.
 */
public final class SneakRampModule extends LauncherModule {

    public float rampTicks = 4f;

    @Override
    protected void settings(SettingList s) {
        s.slider("rampTicks", "Durée de transition (ticks)", "Réglages", 1f, 20f, 1f,
            () -> rampTicks, v -> rampTicks = v);
    }

    public SneakRampModule() {
        super("sneak-ramp-1-7", "Sneak progressif", "Ralentissement du sneak appliqué progressivement au lieu d'instantané", false);
    }
}
