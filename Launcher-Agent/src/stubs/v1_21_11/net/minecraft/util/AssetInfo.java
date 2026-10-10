package net.minecraft.util;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code iu}, une interface en jeu) — une
 * ressource du client. Seule la texture nous sert : c'est par elle qu'un
 * {@code SkinTextures} désigne son image. Pendant de {@code ClientAsset} en
 * 26.1.2.
 */
public interface AssetInfo {

    /** {@code iu$c}, une interface en jeu. */
    interface TextureAsset extends AssetInfo {
    }

    /**
     * {@code iu$a}, un record — une texture venue d'ailleurs que des
     * ressources du jeu : son nom au gestionnaire de textures, et son origine.
     */
    final class SkinAssetInfo implements TextureAsset {

        public SkinAssetInfo(Identifier texturePath, String url) {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
