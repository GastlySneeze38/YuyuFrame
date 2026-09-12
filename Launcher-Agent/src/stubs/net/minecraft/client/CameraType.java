package net.minecraft.client;

/**
 * Stub compile-only (26.1+) — juste assez pour référencer les constantes
 * publiques {@code THIRD_PERSON_BACK}/{@code THIRD_PERSON_FRONT} directement
 * (voir {@code FreelookModule}), vérifiées par désassemblage bytecode du vrai
 * jar 26.1.2 (voir javadoc de {@code FreelookModule#onTick}). Jamais
 * instancié — {@code = null} n'est qu'un placeholder de compilation, la VRAIE
 * constante du jeu répond au runtime (même principe que tous les autres
 * stubs de ce projet).
 */
public abstract class CameraType {
    public static final CameraType FIRST_PERSON = null;
    public static final CameraType THIRD_PERSON_BACK = null;
    public static final CameraType THIRD_PERSON_FRONT = null;
}
