package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Arrête de rendre les tile entities (coffres, fours, spawners, panneaux,
 * têtes, bannières...) au-delà d'une distance configurable — leur rendu
 * passe par {@code BlockEntityRenderDispatcher.renderEntity()}, appelé pour
 * CHAQUE tile entity chargée à chaque frame, peu importe sa distance
 * réelle à la caméra (contrairement aux chunks, pas de culling par défaut
 * ici). Même principe que "Tile Entity Render Distance" de PolyPatcher.
 */
public final class TileEntityRenderDistanceModule extends LauncherModule {

    @ConfigSlider(name = "Distance max (blocs)", category = "Réglages", min = 8f, max = 128f, step = 8f)
    public float maxDistance = 64f;

    public TileEntityRenderDistanceModule() {
        // Nom raccourci (était "Distance de rendu (tile entities)") — voir
        // PlayerBackfaceCullingModule pour le pourquoi (onglet groupé
        // "Distance de rendu" du même nom, voir ModuleRegistry).
        super("tile-entity-render-distance", "Tile entities", "Ne rend pas les coffres/fours/panneaux... au-delà d'une distance donnée", true);
    }
}
