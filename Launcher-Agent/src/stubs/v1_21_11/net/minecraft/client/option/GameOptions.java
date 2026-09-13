package net.minecraft.client.option;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gfo}).
 *
 * <p>{@code fov}, {@code gamma} et {@code mouseSensitivity} sont PRIVÉS
 * (vérifié javap) mais exposés par des getters publics qui rendent la poignée
 * d'option ; {@code hudHidden} est un booléen public.
 */
public class GameOptions {

    public boolean hudHidden;

    private GameOptions() {
    }

    public SimpleOption getFov() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public SimpleOption getGamma() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public SimpleOption getMouseSensitivity() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    // Raccourcis de déplacement — champs PUBLICS (vérifié javap). Renommés par
    // rapport à la 26.1.2 : keyUp/keyLeft/keyDown/keyRight/keyJump y deviennent
    // forwardKey/leftKey/backKey/rightKey/jumpKey ici.
    public KeyBinding forwardKey;
    public KeyBinding leftKey;
    public KeyBinding backKey;
    public KeyBinding rightKey;
    public KeyBinding jumpKey;

    /** Point de vue courant — {@code getCameraType()} en 26.1.2. */
    public Perspective getPerspective() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Écriture du point de vue — {@code setCameraType(CameraType)} en 26.1.2. */
    public void setPerspective(Perspective perspective) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
