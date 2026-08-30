package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Le rendu des nuages "Fancy" (grille 3D de cubes) régénère TOUTE sa
 * géométrie à la main, à CHAQUE FRAME, via le Tessellator (aucune mise en
 * cache — vérifié par désassemblage bytecode réel : 1499 lignes de bytecode
 * dans cette seule méthode, appelée sans conditions à chaque frame où le
 * ciel est rendu). Les nuages bougent lentement et de façon quasi
 * imperceptible d'une frame à l'autre — ce module capture ce rendu dans une
 * display list OpenGL (voir MixinCachedFancyClouds189) et la rejoue au lieu
 * de tout recalculer, ne reconstruisant réellement que toutes les
 * {@code rebuildIntervalMs} millisecondes.
 */
public final class CachedFancyCloudsModule extends LauncherModule {

    public float rebuildIntervalMs = 500f;

    @Override
    protected void settings(SettingList s) {
        s.slider("rebuildIntervalMs", "Intervalle de reconstruction (ms)", "Réglages", 100f, 2000f, 100f,
            () -> rebuildIntervalMs, v -> rebuildIntervalMs = v);
    }

    public CachedFancyCloudsModule() {
        super("cached-fancy-clouds", "Nuages Fancy mis en cache", "Ne recalcule les nuages \"Fancy\" que périodiquement au lieu de chaque frame (aucun effet en mode Fast/Off)", true);
    }
}
