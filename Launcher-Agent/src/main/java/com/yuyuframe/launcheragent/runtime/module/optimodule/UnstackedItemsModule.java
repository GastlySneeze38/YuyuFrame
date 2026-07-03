package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Items au sol non empilés visuellement — le vanilla dessine jusqu'à 5 copies
 * décalées aléatoirement du modèle d'un item au sol selon la taille du stack
 * (voir {@code ItemEntityRenderer}, méthode privée qui calcule ce nombre de
 * copies). Activé, on force ce nombre à 1 : un seul modèle rendu par entité
 * item, peu importe la taille du stack — gain net dans les scènes avec
 * beaucoup de loot (PvP, farms), sans rien changer au jeu (le stack réel n'est
 * pas affecté, juste son rendu). Même principe que PolyPatcher.
 */
public final class UnstackedItemsModule extends LauncherModule {

    public UnstackedItemsModule() {
        super("unstacked-items", "Items non empilés", "Rend une seule copie par item au sol, peu importe la taille du stack", true);
    }
}
