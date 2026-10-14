package com.yuyuframe.launcheragent.apimixin.data;

/**
 * « Le serveur met un groupe d'objets de notre joueur en recharge », en
 * données NEUTRES — contexte de {@code HookPoint.ITEM_COOLDOWN}.
 *
 * <p>Sert au scoring pour reconnaître un bouclier désactivé par une hache : le
 * serveur le met en recharge (docs/scoring/familles/defense.md). Les perles et
 * charges de vent passent aussi par là.
 */
public final class ItemCooldown {

    /** Chemin du groupe de recharge, sans espace de noms ({@code "shield"}, {@code "ender_pearl"}). */
    public final String group;
    /** Durée de la recharge, en ticks ; 0 quand elle est levée. */
    public final int ticks;

    public ItemCooldown(String group, int ticks) {
        this.group = group;
        this.ticks = ticks;
    }
}
