package com.yuyuframe.launcheragent.apimixin.data;

/**
 * « Le serveur joue un son à une position », en données NEUTRES — contexte de
 * {@code HookPoint.SOUND_PLAYED}.
 *
 * <p>Sert au scoring pour les sons qu'aucun autre paquet ne trahit : un coup
 * arrêté au bouclier n'envoie PAS de paquet de dégâts, seulement le son du
 * blocage, joué à la position de celui qui bloque (docs/scoring/familles/defense.md).
 */
public final class SoundPlayed {

    /**
     * Chemin de registre du son, sans espace de noms
     * ({@code "item.shield.block"}) — ou {@code null} pour un son sans
     * identifiant de registre.
     */
    public final String sound;
    /** Position où le son est joué, en blocs. */
    public final double x, y, z;

    public SoundPlayed(String sound, double x, double y, double z) {
        this.sound = sound;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
