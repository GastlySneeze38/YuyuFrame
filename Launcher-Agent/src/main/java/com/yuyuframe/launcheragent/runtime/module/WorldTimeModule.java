package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Temps du monde (client-only) — force l'heure de rendu (soleil/lune,
 * couleur du ciel, éclairage ambiant) à une valeur choisie, INSTANTANÉMENT,
 * SANS toucher au vrai temps du monde (qui reste géré par le serveur —
 * spawn de mobs, etc. inchangés) : demandé explicitement pour pouvoir
 * tester du rendu (shaders) à n'importe quelle heure sans attendre le vrai
 * cycle jour/nuit. Juste un marqueur + le réglage d'heure ici, toute la
 * logique vit dans des Mixins bracket-spécifiques (architectures du temps
 * de rendu incompatibles entre versions — TOUS les brackets couverts,
 * audit demandé explicitement par l'utilisateur, vérifié par désassemblage
 * bytecode + mappings officiels Mojang téléchargés pour chaque version) :
 *   - {@code MixinWorldTime189} (1.8.9) — override {@code World.getSkyAngle(float)F}.
 *   - {@code WorldTimeMixin116}/{@code WorldTimeMixin1204}/{@code WorldTimeMixin1214}/
 *     {@code WorldTimeMixin} (1.16.5/1.20.4/1.21.4/1.21.11) — override
 *     {@code World.getTimeOfDay()J} (nom Yarn ; réel Mojang {@code getDayTime}) —
 *     la source RAW dont dérive tout calcul de rendu du temps sur ces 4
 *     brackets, ancien ou nouveau système. Garde {@code ClientWorld}
 *     obligatoire (classe partagée client/serveur, voir leur javadoc).
 *   - {@code ClientClockManagerWorldTimeMixin261} (26.1.2) — override
 *     {@code ClientClockManager.getTotalTicks(Holder)J}, LA SOURCE UNIQUE
 *     dont dérive tout le nouveau système "EnvironmentAttribute" (sunAngle,
 *     skyColor, éclairage ambiant...) — voir sa javadoc. Déjà client-only
 *     PAR CONSTRUCTION (classe qui n'existe que côté client), pas besoin
 *     du même garde instanceof que les autres brackets.
 */
public final class WorldTimeModule extends LauncherModule {

    @ConfigSlider(name = "Heure (ticks, 0-24000)", category = "Réglages", min = 0f, max = 24000f, step = 500f)
    public float time = 6000f; // 6000 = midi

    public WorldTimeModule() {
        super("world-time", "Temps du monde", "Force l'heure affichée (soleil/lune/ciel), sans changer le vrai temps serveur",
            "Force l'heure affichée", false);
        iconUrl = icons8("clock");
        // 26.1.2 — voir apimixin/v26_1/clock/ClockTotalTicksMixin261.
        VanillaHookRegistry.registerValue(HookPoint.CLOCK_TOTAL_TICKS, ctx -> isEnabled() ? (Long) (long) time : null);
    }
}
