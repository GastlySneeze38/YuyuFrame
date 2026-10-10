package net.minecraft.client.texture;

import net.minecraft.util.Identifier;

/** Stub compile-only 1.21.11, nom Yarn ({@code ilr}) — le gestionnaire de textures du jeu. */
public class TextureManager {

    private TextureManager() {
    }

    /** {@code ilr.a(amo, ikz)} — inscrit une texture sous ce nom ; celle qui le portait est fermée. */
    public void registerTexture(Identifier id, AbstractTexture texture) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ilr.c(amo)} — retire et ferme la texture de ce nom. */
    public void destroyTexture(Identifier id) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
