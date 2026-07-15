package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Équivalent "Clear Water/Lava/Powder Snow" — supprime le brouillard
 * teinté (eau/lave/neige poudreuse) ET, pour la neige poudreuse, l'overlay
 * de givre à l'écran. Aucune logique ici : lu directement par 4 Mixins via
 * {@code ModuleRegistry.get("clear-vision")} :
 * - {@code WaterFogEnvironmentMixin261}/{@code LavaFogEnvironmentMixin261}/
 *   {@code PowderedSnowFogEnvironmentMixin261} (force {@code isApplicable()}
 *   à {@code false} — DÉCOUVERTE : en 26.1+, la vision teintée eau/lave/
 *   neige est ENTIÈREMENT gérée par le nouveau système de brouillard par
 *   "FogEnvironment", pas par un overlay séparé — voir mémoire du portage).
 * - {@code ClearOverlaysMixin261} (annule spécifiquement l'overlay givre de
 *   neige poudreuse, texture "powder_snow_outline", indépendant du
 *   brouillard).
 *
 * Volontairement SÉPARÉ de {@link NoFogModule} : ce dernier coupe TOUT le
 * brouillard (y compris la distance de rendu/le brouillard atmosphérique
 * normal) — celui-ci ne vise QUE les 3 environnements liquide/poudre,
 * laisse le brouillard normal intact.
 *
 * 26.1.2 UNIQUEMENT pour l'instant (voir mémoire du portage).
 */
public final class ClearVisionModule extends LauncherModule {
    public ClearVisionModule() {
        super("clear-vision", "Vision claire (eau/lave/neige)", "Retire le brouillard teinté et le givre de l'eau, la lave et la neige poudreuse", false);
    }
}
