package com.mojang.authlib.properties;

/**
 * Stub compile-only — les propriétés d'un profil (authlib, bibliothèque
 * fournie avec le jeu). La vraie classe étend {@code ForwardingMultimap} de
 * Guava, dont viennent ces deux méthodes (vérifié javap, authlib 7.0.63 à
 * 10.0.77) : les déclarer ici évite de nommer Guava à la compilation.
 */
public class PropertyMap {
    public boolean containsKey(Object key) { return false; }

    public java.util.Collection<Property> get(Object key) { return null; }
}
