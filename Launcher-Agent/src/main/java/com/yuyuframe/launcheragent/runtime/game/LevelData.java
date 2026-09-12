package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

/**
 * Accès PARTAGÉ au monde — <b>zéro réflexion, zéro type du jeu</b>. Cinquième
 * façade, après {@link ClientData}, {@link PlayerData}, {@link ChatData} et
 * {@link NetworkData}.
 *
 * <p>Volontairement minuscule : le seul besoin réel est le biome. Le monde
 * lui-même reste accessible par {@code PlayerData.level()}, qui nomme son type
 * et ne sert donc qu'à la 26.1.2.
 */
public final class LevelData {
    private LevelData() {}

    /**
     * Identifiant de registre COMPLET du biome à ces coordonnées de bloc
     * ({@code "minecraft:plains"}), ou {@code null} hors partie / si le biome
     * n'est pas lisible.
     *
     * <p>L'appelant en extrait ce qu'il veut : le chemin seul pour
     * l'affichage, l'identifiant complet pour comparer. Voir
     * {@link AccessPoint#LEVEL_BIOME_ID} pour pourquoi les deux versions n'ont
     * aucune étape commune ici.
     */
    public static String biomeId(int x, int y, int z) {
        Object v = AccessorRegistry.invoke(AccessPoint.LEVEL_BIOME_ID, null,
            Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        return v instanceof String ? (String) v : null;
    }
}
