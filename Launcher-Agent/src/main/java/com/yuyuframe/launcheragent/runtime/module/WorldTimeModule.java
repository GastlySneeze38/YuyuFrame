package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Temps du monde (client-only) — force l'heure de rendu (soleil/lune,
 * couleur du ciel, éclairage ambiant) à une valeur choisie, INSTANTANÉMENT,
 * SANS toucher au vrai temps du monde (qui reste géré par le serveur —
 * spawn de mobs, etc. inchangés) : demandé explicitement pour pouvoir
 * tester du rendu (shaders) à n'importe quelle heure sans attendre le vrai
 * cycle jour/nuit. Juste un marqueur + le réglage d'heure ici, toute la
 * logique vit dans deux Mixins bracket-spécifiques (architectures du temps
 * de rendu incompatibles entre versions) :
 *   - {@code MixinWorldTime189} (1.8.9) — override {@code World.getSkyAngle(float)F}.
 *   - {@code ClientClockManagerWorldTimeMixin261} (26.1.2) — override
 *     {@code ClientClockManager.getTotalTicks(Holder)J}, LA SOURCE UNIQUE
 *     dont dérive tout le nouveau système "EnvironmentAttribute" (sunAngle,
 *     skyColor, éclairage ambiant...) — voir sa javadoc.
 */
public final class WorldTimeModule extends LauncherModule {

    @ConfigSlider(name = "Heure (ticks, 0-24000)", category = "Réglages", min = 0f, max = 24000f, step = 500f)
    public float time = 6000f; // 6000 = midi

    public WorldTimeModule() {
        super("world-time", "Temps du monde", "Force l'heure affichée (soleil/lune/ciel), sans changer le vrai temps serveur", false);
    }
}
