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
}
