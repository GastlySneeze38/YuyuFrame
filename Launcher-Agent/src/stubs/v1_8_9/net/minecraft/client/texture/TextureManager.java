package net.minecraft.client.texture;

import net.minecraft.util.Identifier;

/** Stub compile-only 1.8.9, nom Yarn legacy ({@code bmj}). */
public class TextureManager {

    private TextureManager() {
    }

    public void bindTexture(Identifier id) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bmj.a(jy, bmk)} — inscrit une texture sous ce nom. */
    public boolean loadTexture(Identifier id, Texture texture) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bmj.c(jy)} — retire la texture de ce nom et la libère. */
    public void close(Identifier id) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
