package com.yuyuframe.launcheragent.runtime.skin;

import java.awt.image.BufferedImage;

/**
 * Met un skin au format que le jeu dessine : 64×64, couche de base opaque.
 *
 * <p>C'est le traitement que le jeu applique lui-même à un skin téléchargé
 * ({@code SkinTextureDownloader.processLegacySkin} sur les versions récentes,
 * {@code ImageBufferDownload} en 1.8.9). Notre skin ne passe pas par ce
 * téléchargement, il faut donc le refaire — sans quoi un ancien skin 64×32
 * n'aurait ni bras ni jambe gauches, et un trou dans la couche de base
 * laisserait voir à travers le joueur.
 */
final class SkinPixels {

    private SkinPixels() {
    }

    private static final int W = OwnSkin.SIZE;

    /** Les pixels d'un skin 64×64 ou 64×32, rendus en 64×64 ARGB. */
    static int[] modern(BufferedImage image) {
        boolean legacy = image.getHeight() == W / 2;
        int[] px = new int[W * W];
        image.getRGB(0, 0, W, image.getHeight(), px, 0, W);
        if (legacy) {
            // Le format d'avant la 1.8 n'a qu'un bras et qu'une jambe : les
            // membres gauches sont les droits, retournés.
            copy(px, 4, 16, 16, 32, 4, 4);
            copy(px, 8, 16, 16, 32, 4, 4);
            copy(px, 0, 20, 24, 32, 4, 12);
            copy(px, 4, 20, 16, 32, 4, 12);
            copy(px, 8, 20, 8, 32, 4, 12);
            copy(px, 12, 20, 16, 32, 4, 12);
            copy(px, 44, 16, -8, 32, 4, 4);
            copy(px, 48, 16, -8, 32, 4, 4);
            copy(px, 40, 20, 0, 32, 4, 12);
            copy(px, 44, 20, -8, 32, 4, 12);
            copy(px, 48, 20, -16, 32, 4, 12);
            copy(px, 52, 20, -8, 32, 4, 12);
        }
        opaque(px, 0, 0, 32, 16);
        if (legacy) {
            clearIfSolid(px, 32, 0, 64, 32);
        }
        opaque(px, 0, 16, 64, 32);
        opaque(px, 16, 48, 48, 64);
        return px;
    }

    /** Recopie un rectangle en le retournant de gauche à droite, décalé de (dx, dy). */
    private static void copy(int[] px, int x, int y, int dx, int dy, int w, int h) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                px[(y + dy + row) * W + x + dx + (w - 1 - col)] = px[(y + row) * W + x + col];
            }
        }
    }

    private static void opaque(int[] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y * W + x] |= 0xFF000000;
            }
        }
    }

    /**
     * Les anciens skins n'avaient pas de transparence : une surcouche de tête
     * entièrement opaque y voulait dire « vide ». Si aucun pixel de la zone
     * n'est transparent, elle est donc effacée.
     */
    private static void clearIfSolid(int[] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((px[y * W + x] >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y * W + x] &= 0x00FFFFFF;
            }
        }
    }
}
