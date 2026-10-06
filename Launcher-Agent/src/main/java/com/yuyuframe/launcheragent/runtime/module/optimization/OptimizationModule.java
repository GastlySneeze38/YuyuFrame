package com.yuyuframe.launcheragent.runtime.module.optimization;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Les optimisations de la 1.8.9 écrites en natif (docs/LauncherAgent/v1.8.9/
 * README.md). UN module, pas un groupe : il s'active ou se coupe d'un bloc,
 * et son écran n'a rien à régler — il explique ce qui tourne, une section
 * par optimisation.
 *
 * <p>Aujourd'hui : la souris brute (§ 9 bis) et le plein écran sans bordure.
 * Le renderer s'y ajoutera, avec sa propre section dans {@link #settings}.
 */
public final class OptimizationModule extends LauncherModule {

    public OptimizationModule() {
        super("optimization", "Optimisation", "Souris brute, sans perte de FPS", true);
        iconUrl = icons8("speed");
    }

    // Textes : clés de traduction (resources/lang/*.json) — les modifier ici,
    // c'est les modifier dans chaque langue.
    static final String MOUSE_TITLE = "Souris brute";
    static final String MOUSE_TEXT =
        "Windows envoie d'ordinaire chaque mouvement de souris à la fenêtre du jeu, un par un. "
            + "Avec une souris à haute fréquence, le jeu passe son temps à les traiter et les FPS chutent. "
            + "Ici, une tâche à part lit la souris directement au niveau du système, sans accélération, "
            + "et additionne les mouvements de son côté. "
            + "Le jeu lui demande le total une fois par image, juste avant de tourner la caméra : "
            + "aucun mouvement perdu, et aucun travail en plus pour le jeu.";

    /**
     * Plein écran sans bordure au lieu du plein écran exclusif. Éteint par
     * défaut : l'exclusif est le comportement d'origine du jeu.
     */
    private boolean borderlessFullscreen = false;

    static final String RENDERER_TITLE = "Renderer de terrain";
    static final String RENDERER_TEXT =
        "Le jeu d'origine envoie chaque chunk à la carte graphique un par un, et recalcule souvent ce qui est visible. "
            + "Ce renderer regroupe les chunks par régions pour les dessiner en quelques appels, "
            + "les construit sur d'autres cœurs du processeur, et n'envoie pas ce qui est caché ou hors de vue. "
            + "Il repose sur Celeritas, un projet libre.";

    /**
     * Renderer de terrain repris de Celeritas ({@code launcheragent/celeritas}).
     * Éteint par défaut tant qu'il n'est pas validé en jeu.
     */
    private boolean terrainRenderer = false;

    @Override
    protected void settings(SettingList list) {
        list.info("renderer-info", RENDERER_TITLE, RENDERER_TEXT, "Renderer");
        list.toggle("terrain-renderer", "Renderer de terrain (expérimental)",
            "Remplace le rendu des chunks du jeu. Les chunks se rechargent à chaque changement de ce réglage.",
            "Renderer", null, () -> terrainRenderer, v -> terrainRenderer = v);
        list.info("mouse-info", MOUSE_TITLE, MOUSE_TEXT, "Souris");
        list.toggle("borderless-fullscreen", "Plein écran sans bordure",
            "Le plein écran devient une fenêtre sans cadre qui couvre l'écran : alt-tab immédiat, sans écran noir.",
            "Affichage", null, () -> borderlessFullscreen, v -> borderlessFullscreen = v);    }

    // Les deux : onEnabledChanged ne part que sur un CHANGEMENT, et
    // onConfigChanged est appelé une fois à l'enregistrement du module, quand
    // l'état relu du disque est le même que le défaut.
    @Override
    protected void onEnabledChanged(boolean enabled) {
        apply();
    }

    @Override
    public void onConfigChanged() {
        apply();
    }

    /** Module coupé = tout revient au comportement d'origine, réglages compris. */
    private void apply() {
        boolean on = isEnabled();
        ClientData.setNativeMouse(on);
        ClientData.setBorderlessFullscreen(on && borderlessFullscreen);
        // Sans réglage : le texte regroupé vient avec le module, comme la souris brute.
        ClientData.setTextBatching(on);

        // Le renderer ne change d'état qu'au rechargement des chunks : on ne le
        // demande que si la consigne a réellement changé.
        boolean wantRenderer = on && terrainRenderer;
        if (wantRenderer != RendererSwitch.wanted()) {
            RendererSwitch.setWanted(wantRenderer);
            ClientData.reloadTerrain();
        }
    }
}
