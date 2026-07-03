package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Le plein écran natif (F11, LWJGL2 Display.setFullscreen) change de mode
 * vidéo — sur pas mal de configs 1.8.9/Windows ça se traduit par de
 * l'instabilité à l'alt-tab (écran noir, minimisation, plantage driver GPU).
 * Ce module ne remplace PAS F11 par un raccourci différent : la carte
 * ci-dessous n'est QU'un interrupteur — tant qu'il est activé, la touche F11
 * (ou le bouton "Plein écran" des options vidéo, même méthode vanilla
 * derrière les deux) bascule vers un plein écran fenêtré SANS bordure
 * (fenêtre normale redimensionnée sur tout l'écran, juste sans cadre) plutôt
 * que le vrai plein écran exclusif — voir MixinBorderlessWindow189 (point
 * d'interception) et BorderlessWindowNative (manipulation Win32 réelle).
 * Désactivé, F11 revient au comportement 100% vanilla.
 */
public final class BorderlessWindowModule extends LauncherModule {
    public BorderlessWindowModule() {
        super("borderless-window", "Fenêtre sans bordure",
            "F11 bascule vers un plein écran fenêtré sans bordure (au lieu du plein écran natif) — alt-tab/changement d'appli stable et instantané", false);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        // Filet de sécurité : si on désactive le module PENDANT qu'on est
        // réellement en mode sans bordure (l'utilisateur avait basculé via F11
        // puis décoche la carte sans repasser par F11), on restaure tout de
        // suite la fenêtre normale — sinon plus aucun moyen de sortir de cet
        // état tant que le module reste désactivé (un futur F11 tomberait sur
        // le chemin vanilla, qui ignore cet état).
        if (!enabled && BorderlessWindowNative.isActive()) {
            BorderlessWindowNative.exitBorderless();
        }
    }
}
