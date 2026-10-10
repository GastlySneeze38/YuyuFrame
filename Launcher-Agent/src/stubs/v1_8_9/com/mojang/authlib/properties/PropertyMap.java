package com.mojang.authlib.properties;

/**
 * Stub compile-only — les propriétés d'un profil (authlib, bibliothèque
 * fournie avec le jeu, jamais traduite). La vraie classe étend
 * {@code ForwardingMultimap} de Guava, dont viennent ces deux méthodes
 * (vérifié javap, authlib 1.5.21) : les déclarer ici évite de nommer Guava à
 * la compilation.
 */
public class PropertyMap {

    public boolean containsKey(Object key) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public java.util.Collection<Property> get(Object key) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
