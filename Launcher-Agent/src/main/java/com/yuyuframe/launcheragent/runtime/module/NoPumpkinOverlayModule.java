package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Retire l'overlay de vision (le "blur" de citrouille) affiché en portant
 * une citrouille sculptée sur la tête. Aucune logique ici : le toggle de la
 * carte est lu directement par {@code ClearOverlaysMixin261} via {@code
 * ModuleRegistry.get("no-pumpkin-overlay")} — voir sa javadoc pour le
 * mécanisme réel (26.1+ : {@code Gui.extractTextureOverlay(...)}, filtré
 * par le chemin de texture "pumpkin", partagé avec la neige poudreuse mais
 * annulé indépendamment selon quel module est actif).
 *
 * 26.1.2 UNIQUEMENT pour l'instant (voir mémoire du portage) — pas encore
 * porté vers 1.8.9/1.16.5/1.20.4/1.21.4.
 */
public final class NoPumpkinOverlayModule extends LauncherModule {
    public NoPumpkinOverlayModule() {
        super("no-pumpkin-overlay", "Sans citrouille (vision)", "Retire l'overlay de vision de la citrouille sculptée portée sur la tête", false);
    }
}
