package com.yuyuframe.launcheragent.apimixin.data;

/**
 * « Le joueur utilise l'objet tenu », en données NEUTRES — contexte de
 * {@code HookPoint.ITEM_USE}.
 *
 * <p>C'est le geste du joueur, relevé côté client : le serveur peut encore le
 * refuser (délai de réutilisation, mode spectateur).
 */
public final class ItemUse {

    /**
     * Identifiant de registre de l'objet, sans espace de noms
     * ({@code "wind_charge"}, {@code "ender_pearl"}) — ou {@code null} si la
     * main est vide.
     */
    public final String item;
    /** Main principale ; faux pour la main secondaire. */
    public final boolean mainHand;

    public ItemUse(String item, boolean mainHand) {
        this.item = item;
        this.mainHand = mainHand;
    }
}
