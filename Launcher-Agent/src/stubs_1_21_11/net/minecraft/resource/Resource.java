package net.minecraft.resource;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code bax}) — une ressource du jeu,
 * resource packs compris.
 *
 * <p>C'est par ELLE qu'il faut charger une texture que l'on veut voir suivre
 * les packs : le classloader, lui, sert la version du jar et les ignore.
 */
public class Resource {

    protected Resource() {
    }

    public java.io.InputStream getInputStream() throws java.io.IOException {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
