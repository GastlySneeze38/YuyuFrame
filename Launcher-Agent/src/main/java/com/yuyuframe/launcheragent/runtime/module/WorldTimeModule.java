package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Temps du monde (client-only) — force la position soleil/lune/couleur du
 * ciel à une heure choisie, SANS toucher au vrai temps du monde (qui reste
 * géré par le serveur — spawn de mobs, etc. inchangés). Juste un marqueur +
 * le réglage d'heure ici, toute la logique vit dans
 * {@code MixinWorldTime189} (override de {@code World.getSkyAngle(float)},
 * qui pilote uniquement le RENDU).
 */
public final class WorldTimeModule extends LauncherModule {

    @ConfigSlider(name = "Heure (ticks, 0-24000)", category = "Réglages", min = 0f, max = 24000f, step = 500f)
    public float time = 6000f; // 6000 = midi

    public WorldTimeModule() {
        super("world-time", "Temps du monde", "Force l'heure affichée (soleil/lune/ciel), sans changer le vrai temps serveur", false);
    }
}
