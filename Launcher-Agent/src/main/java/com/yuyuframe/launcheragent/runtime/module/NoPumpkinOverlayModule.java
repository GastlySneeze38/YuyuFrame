package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Retire l'overlay de vision (le "blur" de citrouille) affiché en portant
 * une citrouille sculptée sur la tête. Aucune logique ici : le toggle de la
 * carte est lu directement par les Mixins bracket-spécifiques via {@code
 * ModuleRegistry.get("no-pumpkin-overlay")} :
 *   - {@code ClearOverlaysMixin261} (26.1.2) — {@code
 *     Gui.extractTextureOverlay(...)}.
 *   - {@code ClearOverlaysMixin} (1.21.11) — {@code
 *     Gui.renderTextureOverlay(...)}/{@code InGameHud.renderOverlay(...)}
 *     (Yarn) : même point de passage commun, pas encore la séparation
 *     extraction/rendu de 26.1.2, sinon architecture identique.
 * Chacun filtre par le chemin de texture "pumpkin", partagé avec la neige
 * poudreuse mais annulé indépendamment selon quel module est actif.
 *
 * 1.16.5/1.20.4/1.21.4/1.8.9 pas encore portés (architecture de rendu de
 * l'overlay à revérifier séparément pour chacun).
 */
public final class NoPumpkinOverlayModule extends LauncherModule {
    public NoPumpkinOverlayModule() {
        // Nom raccourci (était "Sans citrouille (vision)") — retour
        // utilisateur : débordait de la sous-sidebar du groupe "Confort
        // visuel" ; le détail reste dans la description.
        super("no-pumpkin-overlay", "Sans citrouille", "Retire l'overlay de vision de la citrouille sculptée portée sur la tête", false);
    }
}
