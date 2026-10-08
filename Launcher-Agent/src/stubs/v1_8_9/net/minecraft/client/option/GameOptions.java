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

    /** {@code avh.aC} — l'écran de débogage (F3) est affiché. */
    public boolean debugEnabled;

    public KeyBinding forwardKey;

    public KeyBinding leftKey;

    public KeyBinding backKey;

    public KeyBinding rightKey;

    public KeyBinding jumpKey;

    public KeyBinding sneakKey;

    public KeyBinding sprintKey;

    public KeyBinding attackKey;

    public KeyBinding useKey;

    /** {@code avh.g} — {@code fpsLimit} chez Feather. */
    public int maxFramerate;

    /** {@code avh.i} — même nom chez Feather. */
    public boolean fancyGraphics;

    /** {@code avh.t} — même nom chez Feather. */
    public boolean vsync;

    /** {@code avh.e} — mode 3D rouge/cyan. */
    public boolean anaglyph3d;

    /** {@code avh.c} — distance de rendu, en chunks. */
    public int viewDistance;

    private GameOptions() {
    }
}
