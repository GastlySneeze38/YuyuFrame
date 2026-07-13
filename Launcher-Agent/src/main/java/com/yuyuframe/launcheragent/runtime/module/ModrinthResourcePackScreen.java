package com.yuyuframe.launcheragent.runtime.module;

/**
 * Recherche/installation de resource packs Modrinth — sous-classe fine de
 * {@link ModrinthContentScreen} (logique recherche/install/icônes partagée
 * avec {@link ModrinthShaderPackScreen}). Voir la javadoc de la classe de
 * base pour le contexte ancien système vs. nouveau.
 */
public final class ModrinthResourcePackScreen extends ModrinthContentScreen {

    public ModrinthResourcePackScreen(Object lastScreen) {
        super("Modrinth", lastScreen, "resourcepacks", "resourcepack",
            "Rechercher un resource pack...", "Modrinth — Resource Packs");
    }
}
