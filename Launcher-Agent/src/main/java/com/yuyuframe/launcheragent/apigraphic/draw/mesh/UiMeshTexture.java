package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * La texture d'un modèle, préparée pour les pipelines d'icônes du moteur.
 *
 * <p>Deux retouches, faites une fois par clé :
 * <ul>
 *   <li><b>Agrandie au plus proche voisin</b> (jusqu'à 512 px de côté).
 *       L'atlas des icônes filtre en bilinéaire : une texture du jeu de
 *       64 × 64 y deviendrait floue une fois étalée sur 150 pixels d'écran.
 *       Agrandie avant d'être envoyée, ses pixels restent nets — seule leur
 *       frontière est adoucie d'un pixel, ce qui lisse les bords obliques.</li>
 *   <li><b>Une bande blanche</b> ajoutée à droite. Les quads
 *       d'éclaircissement (une lumière plus forte que 1) la visent : ils
 *       partagent ainsi la même image que le modèle, donc le même appel de
 *       dessin.</li>
 * </ul>
 */
public final class UiMeshTexture {

    private static final int TARGET = 512;
    private static final int MAX_FACTOR = 16;
    private static final int WHITE_STRIP = 6;

    private static final Map<String, UiMeshTexture> CACHE = new HashMap<>();

    private final String key;
    private final BufferedImage image;
    private final float uScale;
    private final float whiteU;

    private UiMeshTexture(String key, BufferedImage image, float uScale, float whiteU) {
        this.key = key;
        this.image = image;
        this.uScale = uScale;
        this.whiteU = whiteU;
    }

    /**
     * La texture préparée pour {@code key} — {@code source} n'est lue qu'au
     * premier appel avec cette clé. {@code null} si {@code source} l'est.
     */
    public static UiMeshTexture of(String key, BufferedImage source) {
        UiMeshTexture cached = CACHE.get(key);
        if (cached != null) return cached;
        if (source == null) return null;
        int w = source.getWidth(), h = source.getHeight();
        int factor = Math.max(1, Math.min(MAX_FACTOR, TARGET / Math.max(1, Math.max(w, h))));
        int scaledW = w * factor, scaledH = h * factor;
        int total = scaledW + WHITE_STRIP;
        BufferedImage out = new BufferedImage(total, scaledH, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[total];
        for (int y = 0; y < scaledH; y++) {
            int sourceY = y / factor;
            for (int x = 0; x < scaledW; x++) row[x] = source.getRGB(x / factor, sourceY);
            for (int x = scaledW; x < total; x++) row[x] = 0xFFFFFFFF;
            out.setRGB(0, y, total, 1, row, 0, total);
        }
        UiMeshTexture prepared = new UiMeshTexture("mesh:" + key, out,
            scaledW / (float) total, (scaledW + WHITE_STRIP / 2f) / total);
        CACHE.put(key, prepared);
        return prepared;
    }

    /** Oublie la texture préparée de {@code key} (pack de ressources changé, par exemple). */
    public static void forget(String key) {
        CACHE.remove(key);
    }

    /** Clé de l'image dans l'atlas du moteur. */
    public String key() { return key; }
    public BufferedImage image() { return image; }
    /** Facteur à appliquer aux {@code u} du modèle (la bande blanche prend la fin de l'image). */
    public float uScale() { return uScale; }
    /** {@code u} du milieu de la bande blanche. */
    public float whiteU() { return whiteU; }
}
