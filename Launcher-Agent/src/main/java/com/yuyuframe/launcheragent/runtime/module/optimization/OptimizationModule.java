package com.yuyuframe.launcheragent.runtime.module.optimization;

import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Les optimisations de la 1.8.9 écrites en natif (docs/LauncherAgent/v1.8.9/
 * README.md). UN module, pas un groupe : il s'active ou se coupe d'un bloc,
 * et son écran n'a rien à régler — il explique ce qui tourne, une section
 * par optimisation.
 *
 * <p>Aujourd'hui : la souris brute (§ 9 bis). Le renderer s'y ajoutera, avec
 * sa propre section dans {@link #settings}.
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

    @Override
    protected void settings(SettingList list) {
        list.info("mouse-info", MOUSE_TITLE, MOUSE_TEXT, "Souris");
    }

    // Les deux : onEnabledChanged ne part que sur un CHANGEMENT, et
    // onConfigChanged est appelé une fois à l'enregistrement du module, quand
    // l'état relu du disque est le même que le défaut.
    @Override
    protected void onEnabledChanged(boolean enabled) {
        ClientData.setNativeMouse(enabled);
    }

    @Override
    public void onConfigChanged() {
        ClientData.setNativeMouse(isEnabled());
    }
}
