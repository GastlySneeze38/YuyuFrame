package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Reconnaître en jeu les autres joueurs YuyuFrame.
 *
 * <p>Le module ne fait qu'ouvrir ou fermer la porte : tout le travail est dans
 * le noyau natif ({@code runtime/presence/NativePresence}) et dans le code de
 * chaque version qui marque l'étiquette d'un joueur reconnu (1.8.9 :
 * {@code apimixin/v1_8_9/entity/PresenceLabel189}). Éteint, plus aucune
 * question ne part vers le serveur.
 *
 * <p>Il ne règle QUE ce que ce joueur voit. Apparaître soi-même comme joueur
 * YuyuFrame aux yeux des autres se décide dans le launcher (Paramètres →
 * Confidentialité).
 *
 * <p>1.8.9 seulement pour l'instant, et seulement l'étiquette au-dessus de la
 * tête.
 */
public final class YuyuPlayersModule extends LauncherModule {

    /** Lu à chaque étiquette dessinée, depuis du code qui ne connaît pas le registre des modules. */
    private static volatile YuyuPlayersModule instance;

    public YuyuPlayersModule() {
        super("yuyu-players", "Joueurs YuyuFrame", "Reconnaît en jeu les autres joueurs qui utilisent YuyuFrame", true);
        instance = this;
    }

    /** Faux tant que le module n'est pas enregistré (autre version du jeu) ou s'il est éteint. */
    public static boolean active() {
        YuyuPlayersModule module = instance;
        return module != null && module.isEnabled();
    }
}
