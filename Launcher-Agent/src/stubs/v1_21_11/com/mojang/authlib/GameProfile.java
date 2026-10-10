package com.mojang.authlib;

/**
 * Stub compile-only — bibliothèque fournie avec le jeu, non obfusquée. Un
 * RECORD sur cette version ({@code id()}, {@code name()} — vérifié sur
 * authlib 7.0.61), comme en 26.x.
 */
public final class GameProfile {
    private GameProfile() {
    }

    public java.util.UUID id() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public String name() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Les propriétés du profil — dont {@code textures}, le skin que le serveur donne au joueur. */
    public com.mojang.authlib.properties.PropertyMap properties() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
