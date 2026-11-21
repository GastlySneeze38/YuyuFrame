package com.yuyuframe.launcheragent.runtime.ui.shortcuts;

/**
 * Les raccourcis que l'on peut poser sur l'écran-titre et le menu Échap.
 *
 * <p>L'ordre est celui de l'éditeur et de la rangée d'icônes. L'identifiant
 * sert de clé dans le fichier des réglages ({@code shortcuts.<écran>.<id>}) :
 * ne jamais le renommer, un placement choisi serait perdu.
 */
public enum Shortcut {
    /** Le compte Minecraft et la liste des autres comptes du launcher. Écran-titre seulement. */
    ACCOUNT("account", "Compte", true),
    /** Ouvre le menu YuyuFrame. */
    MENU("menu", "Menu", false),
    /** Missions du jour : l'avancement, et l'onglet Missions au clic. */
    MISSIONS("missions", "Missions", false),
    /** Le rang du meilleur mode, et l'onglet Compétitif au clic. */
    RANKED("ranked", "Compétitif", false),
    /** Les modules favoris, à allumer ou éteindre d'un clic. */
    MODULES("modules", "Modules", false),
    /** Les amis en ligne, et l'onglet Amis au clic. */
    FRIENDS("friends", "Amis", false);

    public final String id;
    /** Libellé en français, passé par {@code Lang.tr} à l'affichage. */
    public final String label;
    /**
     * Changer de compte n'a de sens que hors d'une partie : une fois dans un
     * monde, l'identité est liée à la connexion. Ce raccourci n'existe donc
     * que sur l'écran-titre.
     */
    public final boolean titleOnly;

    Shortcut(String id, String label, boolean titleOnly) {
        this.id = id;
        this.label = label;
        this.titleOnly = titleOnly;
    }

    /** Ce raccourci peut-il exister sur cet écran ? */
    public boolean allowedOn(ShortcutLayout.Screen screen) {
        return !titleOnly || screen == ShortcutLayout.Screen.TITLE;
    }
}
