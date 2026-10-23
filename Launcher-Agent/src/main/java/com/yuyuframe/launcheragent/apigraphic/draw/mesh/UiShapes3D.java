package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.util.Collections;

/**
 * Formes 3D fabriquées par nous, au format {@link UiModel} — le pendant des
 * modèles vanilla quand le jeu n'a pas la forme voulue.
 */
public final class UiShapes3D {

    private UiShapes3D() {
    }

    /**
     * Une tuile : un carré aux coins arrondis, épais, dont les arêtes avant et
     * arrière sont biseautées. Centrée sur l'origine, face avant vers +Z.
     *
     * <p>La texture est lue dans deux zones de la même image :
     * <ul>
     *   <li>{@code faceUv} {@code {u0, v0, u1, v1}} — étalée sur les faces
     *       avant et arrière <b>et</b> sur les biseaux, selon la position :
     *       la texture continue jusqu'au bord, comme un bloc dont on aurait
     *       arrondi les arêtes ;</li>
     *   <li>{@code sideUv} {@code {u, v}} — un point de couleur unie pour la
     *       tranche ;</li>
     *   <li>{@code bevelUv} {@code {u, v}} — un point de couleur unie pour les
     *       biseaux (un liseré), ou {@code null} pour y continuer la texture.</li>
     * </ul>
     * L'éclairage fait le reste : chaque facette du biseau a sa propre
     * orientation, donc sa propre lumière.
     *
     * @param size       côté, en pixels du modèle
     * @param depth      épaisseur totale
     * @param radius     rayon des coins de la face extérieure
     * @param bevel      largeur du biseau (prise sur la face et sur la tranche)
     * @param segments   facettes par quart de cercle
     */
    public static UiModel roundedTile(float size, float depth, float radius, float bevel, int segments,
                                      float[] faceUv, float[] sideUv, float[] bevelUv) {
        float half = size / 2f;
        float zFace = depth / 2f;
        float zEdge = Math.max(0f, zFace - bevel);
        float[][] outer = loop(half, radius, segments);
        float[][] inner = loop(half - bevel, Math.max(0f, radius - bevel), segments);
        int n = outer.length;
        UiModel.PartBuilder tile = new UiModel.PartBuilder("tile");
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            // Face avant (éventail depuis le centre) et face arrière (miroir).
            tile.face(quad(0, 0, zFace, inner[i][0], inner[i][1], zFace, inner[j][0], inner[j][1], zFace, inner[j][0], inner[j][1], zFace),
                faceUvs(faceUv, size, false, 0, 0, inner[i][0], inner[i][1], inner[j][0], inner[j][1], inner[j][0], inner[j][1]), 0, 0, 0);
            tile.face(quad(0, 0, -zFace, inner[j][0], inner[j][1], -zFace, inner[i][0], inner[i][1], -zFace, inner[i][0], inner[i][1], -zFace),
                faceUvs(faceUv, size, true, 0, 0, inner[j][0], inner[j][1], inner[i][0], inner[i][1], inner[i][0], inner[i][1]), 0, 0, 0);
            // Biseaux avant et arrière.
            float[] frontBevel = quad(inner[i][0], inner[i][1], zFace, inner[j][0], inner[j][1], zFace,
                outer[j][0], outer[j][1], zEdge, outer[i][0], outer[i][1], zEdge);
            float[] backBevel = quad(inner[j][0], inner[j][1], -zFace, inner[i][0], inner[i][1], -zFace,
                outer[i][0], outer[i][1], -zEdge, outer[j][0], outer[j][1], -zEdge);
            if (bevelUv != null) {
                tile.face(frontBevel, solid(bevelUv), 0, 0, 0);
                tile.face(backBevel, solid(bevelUv), 0, 0, 0);
            } else {
                tile.face(frontBevel, faceUvs(faceUv, size, false, inner[i][0], inner[i][1], inner[j][0], inner[j][1],
                    outer[j][0], outer[j][1], outer[i][0], outer[i][1]), 0, 0, 0);
                tile.face(backBevel, faceUvs(faceUv, size, true, inner[j][0], inner[j][1], inner[i][0], inner[i][1],
                    outer[i][0], outer[i][1], outer[j][0], outer[j][1]), 0, 0, 0);
            }
            // Tranche.
            if (zEdge > 0f) {
                tile.face(quad(outer[i][0], outer[i][1], zEdge, outer[j][0], outer[j][1], zEdge,
                    outer[j][0], outer[j][1], -zEdge, outer[i][0], outer[i][1], -zEdge), solid(sideUv), 0, 0, 0);
            }
        }
        return new UiModel(Collections.singletonList(tile.build()));
    }

    /** Le contour d'un carré arrondi, sens trigonométrique, en partant du coin haut droit. */
    private static float[][] loop(float half, float radius, int segments) {
        int perCorner = Math.max(1, segments) + 1;
        float[][] points = new float[4 * perCorner][];
        float inset = half - radius;
        float[][] centers = { { inset, inset }, { -inset, inset }, { -inset, -inset }, { inset, -inset } };
        int k = 0;
        for (int corner = 0; corner < 4; corner++) {
            for (int s = 0; s < perCorner; s++) {
                double angle = Math.PI / 2 * corner + Math.PI / 2 * s / (perCorner - 1);
                points[k++] = new float[]{
                    centers[corner][0] + radius * (float) Math.cos(angle),
                    centers[corner][1] + radius * (float) Math.sin(angle),
                };
            }
        }
        return points;
    }

    private static float[] quad(float... values) {
        return values;
    }

    private static float[] solid(float[] uv) {
        return new float[]{ uv[0], uv[1], uv[0], uv[1], uv[0], uv[1], uv[0], uv[1] };
    }

    /** UV d'après la position dans le carré : X de gauche à droite, Y de haut en bas (miroir pour l'arrière). */
    private static float[] faceUvs(float[] rect, float size, boolean mirror, float... xy) {
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            float s = xy[i * 2] / size + 0.5f;
            float t = 0.5f - xy[i * 2 + 1] / size;
            if (mirror) s = 1f - s;
            uv[i * 2] = rect[0] + (rect[2] - rect[0]) * s;
            uv[i * 2 + 1] = rect[1] + (rect[3] - rect[1]) * t;
        }
        return uv;
    }
}
