package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

/**
 * Ce que reçoit un moteur de rendu : des quadrilatères déjà projetés à
 * l'écran, dans l'ordre où les dessiner, chacun texturé et coloré par sommet.
 *
 * <p>Toute la 3D est faite avant : un backend n'a qu'à recopier ces sommets
 * dans le format de son pipeline d'icônes. C'est ce qui garde la brique 3D à
 * un seul endroit pour toutes les versions.
 *
 * <ul>
 *   <li>{@code xy} : 8 valeurs par quad, repère du moteur (pixels du
 *       framebuffer, Y vers le haut), sommets dans le sens inverse des
 *       aiguilles d'une montre — le même que les icônes ;</li>
 *   <li>{@code uv} : 8 valeurs par quad, de 0 à 1 dans l'image passée avec le
 *       lot (le backend les ramène dans son atlas) ;</li>
 *   <li>{@code argb} : 4 couleurs par quad, multipliées par la texture.</li>
 * </ul>
 *
 * <p>Réutilisable : {@link #reset} garde les tableaux, qui ne font que grandir.
 */
public final class UiMeshBatch {

    private float[] xy = new float[64 * 8];
    private float[] uv = new float[64 * 8];
    private int[] argb = new int[64 * 4];
    private int quads;
    private float minX, minY, maxX, maxY;

    public void reset() {
        quads = 0;
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
    }

    /** Ajoute un quad ; les sommets doivent déjà tourner dans le bon sens. */
    public void add(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                    float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3,
                    int c0, int c1, int c2, int c3) {
        if ((quads + 1) * 8 > xy.length) {
            int capacity = xy.length * 2;
            xy = java.util.Arrays.copyOf(xy, capacity);
            uv = java.util.Arrays.copyOf(uv, capacity);
            argb = java.util.Arrays.copyOf(argb, capacity / 2);
        }
        int p = quads * 8, c = quads * 4;
        xy[p] = x0; xy[p + 1] = y0; xy[p + 2] = x1; xy[p + 3] = y1;
        xy[p + 4] = x2; xy[p + 5] = y2; xy[p + 6] = x3; xy[p + 7] = y3;
        uv[p] = u0; uv[p + 1] = v0; uv[p + 2] = u1; uv[p + 3] = v1;
        uv[p + 4] = u2; uv[p + 5] = v2; uv[p + 6] = u3; uv[p + 7] = v3;
        argb[c] = c0; argb[c + 1] = c1; argb[c + 2] = c2; argb[c + 3] = c3;
        minX = Math.min(minX, Math.min(Math.min(x0, x1), Math.min(x2, x3)));
        maxX = Math.max(maxX, Math.max(Math.max(x0, x1), Math.max(x2, x3)));
        minY = Math.min(minY, Math.min(Math.min(y0, y1), Math.min(y2, y3)));
        maxY = Math.max(maxY, Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        quads++;
    }

    /**
     * Applique {@code x -> a·x + bx}, {@code y -> a·y + by} et multiplie
     * l'opacité — pour les calques de transformation ({@code UiLayerBackend}).
     */
    public UiMeshBatch transformed(float a, float bx, float by, float alpha) {
        UiMeshBatch out = new UiMeshBatch();
        out.reset();
        for (int q = 0; q < quads; q++) {
            int p = q * 8, c = q * 4;
            out.add(a * xy[p] + bx, a * xy[p + 1] + by, a * xy[p + 2] + bx, a * xy[p + 3] + by,
                a * xy[p + 4] + bx, a * xy[p + 5] + by, a * xy[p + 6] + bx, a * xy[p + 7] + by,
                uv[p], uv[p + 1], uv[p + 2], uv[p + 3], uv[p + 4], uv[p + 5], uv[p + 6], uv[p + 7],
                fade(argb[c], alpha), fade(argb[c + 1], alpha), fade(argb[c + 2], alpha), fade(argb[c + 3], alpha));
        }
        return out;
    }

    private static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    public int quads() { return quads; }
    public float[] xy() { return xy; }
    public float[] uv() { return uv; }
    public int[] argb() { return argb; }
    public float minX() { return minX; }
    public float minY() { return minY; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }
}
