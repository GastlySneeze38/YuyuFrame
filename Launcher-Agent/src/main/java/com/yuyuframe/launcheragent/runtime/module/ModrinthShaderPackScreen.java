package com.yuyuframe.launcheragent.runtime.module;

/**
 * Recherche/installation de shader packs Modrinth — sous-classe fine de
 * {@link ModrinthContentScreen} (logique recherche/install/icônes partagée
 * avec {@link ModrinthResourcePackScreen}). Remplace l'ancien
 * {@code screen.ShaderPackSearchScreen} (déclenché depuis le bouton
 * "Shaders..." injecté par {@code GameMenuScreenMixin}, gated par
 * {@code ShaderLoaderDetector.isPresent} — cassé sur 1.8.9 pour la même
 * raison que l'ancien resource pack screen, voir javadoc de la classe de base).
 */
public final class ModrinthShaderPackScreen extends ModrinthContentScreen {

    public ModrinthShaderPackScreen(Object lastScreen) {
        super("Modrinth", lastScreen, "shaderpacks", "shader",
            "Rechercher un shader pack...", "Modrinth — Shaders");
    }
}
