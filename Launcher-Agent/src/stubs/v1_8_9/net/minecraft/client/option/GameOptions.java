package net.minecraft.client.option;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code avh}).
 *
 * <p>Pas de {@code SimpleOption} sur cette version : chaque réglage est un
 * champ PUBLIC nu (vérifié javap), en {@code float} pour les trois options à
 * poignée. {@code fov} est en DEGRÉS en mémoire (le fichier options.txt,
 * lui, stocke une valeur normalisée — sans importance ici).
 * {@code perspective} vaut 0 (1re personne), 1 (dos), 2 (face).
 */
public class GameOptions {

    public float sensitivity;

    public float fov;

    public float gamma;

    public boolean hudHidden;

    public int perspective;

    public KeyBinding forwardKey;

    public KeyBinding leftKey;

    public KeyBinding backKey;

    public KeyBinding rightKey;

    public KeyBinding jumpKey;

    public KeyBinding sneakKey;

    public KeyBinding sprintKey;

    public KeyBinding attackKey;

    public KeyBinding useKey;

    private GameOptions() {
    }
}
