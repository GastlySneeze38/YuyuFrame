package com.yuyuframe.launcheragent.apigraphic.draw.geometry;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

/**
 * Validation et normalisation des paliers d'un dégradé multi-stop — <b>calcul
 * pur</b>, sans GL ni jeu.
 *
 * <p>Première brique de {@code draw/geometry/}, extraite de
 * {@code UiPrimitiveRenderer.drawMultiStopGradientRect} le 2026-09-09. Elle y
 * était en tête de méthode, AVANT le branchement d'ère — donc recopiée à
 * l'identique dans chaque chemin dès que la méthode serait découpée. C'est
 * exactement le genre de règle qui doit vivre une seule fois : les shaders
 * attendent huit paliers, quel que soit le nombre fourni.
 */
public final class GradientStops {

    private GradientStops() {}

    /** Nombre de paliers attendu par les shaders des trois ères. */
    public static final int SLOTS = 8;

    /** Paliers prêts pour un shader : toujours {@link #SLOTS} entrées. */
    public static final class Normalized {
        public final UiColor[] colors;
        public final float[] positions;

        Normalized(UiColor[] colors, float[] positions) {
            this.colors = colors;
            this.positions = positions;
        }
    }

    /**
     * Valide puis complète les paliers jusqu'à {@link #SLOTS}.
     *
     * <p>Au-delà de huit paliers fournis, le surplus est ignoré ; en dessous,
     * les emplacements restants répètent le DERNIER palier — de sorte que le
     * shader, qui lit toujours huit entrées, ne voie pas de couleur parasite
     * après la fin du dégradé.
     *
     * @return {@code null} si l'entrée est inutilisable (tableaux absents,
     *         vides, ou de longueurs différentes) — l'appelant ne dessine
     *         alors rien, comme avant l'extraction
     */
    public static Normalized normalize(UiColor[] stopColors, float[] stopPositions) {
        if (stopColors == null || stopPositions == null || stopColors.length == 0
                || stopColors.length != stopPositions.length) {
            return null;
        }
        int n = Math.min(stopColors.length, SLOTS);
        UiColor[] colors = new UiColor[SLOTS];
        float[] positions = new float[SLOTS];
        for (int i = 0; i < n; i++) { colors[i] = stopColors[i]; positions[i] = stopPositions[i]; }
        for (int i = n; i < SLOTS; i++) { colors[i] = colors[n - 1]; positions[i] = positions[n - 1]; }
        return new Normalized(colors, positions);
    }
}
