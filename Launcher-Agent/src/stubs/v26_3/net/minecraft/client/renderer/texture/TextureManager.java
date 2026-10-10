package net.minecraft.client.renderer.texture;

import net.minecraft.resources.Identifier;

/** Stub compile-only (26.1+) — le gestionnaire de textures du jeu (vérifié javap, 26.1 à 26.3). */
public class TextureManager {
    /** Inscrit une texture sous ce nom ; celle qui le portait est fermée. */
    public void register(Identifier id, AbstractTexture texture) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Retire et ferme la texture de ce nom. */
    public void release(Identifier id) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
