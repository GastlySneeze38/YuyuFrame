package com.mojang.authlib;

import java.util.UUID;

/**
 * Stub compile-only 1.8.9 — {@code com.mojang.authlib.GameProfile},
 * bibliothèque fournie avec le jeu (présente au runtime, absente du classpath
 * de compilation de l'agent). Pas obfusquée : ses noms sont les vrais.
 *
 * <p>Sur cette version c'est une classe ordinaire, avec {@code getId()} et
 * {@code getName()} — elle n'est devenue un record ({@code id()},
 * {@code name()}) que bien plus tard, voir le stub de la ligne 26.x.
 *
 * <p>Signature vérifiée sur {@code authlib-1.5.21.jar}.
 */
public class GameProfile {

    private GameProfile() {
    }

    public UUID getId() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public String getName() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Les propriétés du profil — dont {@code textures}, le skin que le serveur donne au joueur. */
    public com.mojang.authlib.properties.PropertyMap getProperties() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
