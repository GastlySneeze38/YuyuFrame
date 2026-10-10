package net.minecraft.core;

import net.minecraft.resources.Identifier;

/**
 * Stub compile-only (26.1+) — une ressource du client. Seule la texture nous
 * sert : c'est par elle qu'un {@code PlayerSkin} désigne son image (vérifié
 * javap, 26.1 à 26.3).
 */
public interface ClientAsset {

    interface Texture extends ClientAsset {
    }

    /** Une texture venue d'ailleurs que des ressources du jeu : son nom au gestionnaire de textures, et son origine. */
    final class DownloadedTexture implements Texture {
        public DownloadedTexture(Identifier texturePath, String url) {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
