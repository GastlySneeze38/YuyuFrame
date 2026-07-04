package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

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

    @ConfigSlider(name = "Durée de transition (ticks)", category = "Réglages", min = 1f, max = 20f, step = 1f)
    public float rampTicks = 4f;

    public SneakRampModule() {
        super("sneak-ramp-1-7", "Sneak progressif", "Ralentissement du sneak appliqué progressivement au lieu d'instantané", false);
    }
}
