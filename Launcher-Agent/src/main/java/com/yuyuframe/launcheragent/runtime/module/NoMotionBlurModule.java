package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Retire l'effet de flou/déformation d'écran des Nausées (Confusion) —
 * appelé "motion blur" par les joueurs, nom interne réel Mojang :
 * {@code Gui.extractConfusionOverlay(...)}. Aucune logique ici : lu
 * directement par {@code NoConfusionOverlayMixin261} via {@code
 * ModuleRegistry.get("no-motion-blur")}.
 *
 * 26.1.2 UNIQUEMENT pour l'instant (voir mémoire du portage).
 */
public final class NoMotionBlurModule extends LauncherModule {
    public NoMotionBlurModule() {
        super("no-motion-blur", "Sans flou de mouvement", "Retire le flou/déformation d'écran de l'effet Nausée", false);
    }
}
