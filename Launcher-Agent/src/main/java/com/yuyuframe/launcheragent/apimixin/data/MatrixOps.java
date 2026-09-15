package com.yuyuframe.launcheragent.apimixin.data;

/**
 * Suite de translations et rotations à appliquer à la matrice courante, en
 * données NEUTRES — la valeur de {@code HookPoint.HELD_ITEM_TRANSFORM}.
 *
 * <p>Le module DÉCRIT la transformation, le mixin de la tranche l'APPLIQUE :
 * un module ne peut pas appeler {@code GlStateManager} (classe du jeu), et le
 * mixin n'a pas à connaître la politique (angle choisi, courbe d'animation).
 *
 * <p>Ordre conservé tel qu'écrit, comme une suite d'appels
 * {@code translate}/{@code rotate} successifs. Rotation en degrés autour de
 * l'axe {@code (x, y, z)}.
 */
public final class MatrixOps {

    /** Aucune opération — utile pour REMPLACER une pose vanilla par rien. */
    public static final MatrixOps EMPTY = new MatrixOps();

    /** Genre d'une opération : {@link #TRANSLATE} ou {@link #ROTATE}. */
    public static final int TRANSLATE = 0;
    public static final int ROTATE = 1;

    /** Cinq nombres par opération : genre, puis {x, y, z, —} ou {angle, x, y, z}. */
    private static final int STRIDE = 5;

    private float[] data = new float[STRIDE * 4];
    private int count;

    public MatrixOps translate(float x, float y, float z) {
        return add(TRANSLATE, x, y, z, 0f);
    }

    public MatrixOps rotate(float angleDegrees, float x, float y, float z) {
        return add(ROTATE, angleDegrees, x, y, z);
    }

    /**
     * Vide la suite pour la réutiliser — un module appelé à chaque image garde
     * UNE instance plutôt que d'en allouer une par image.
     */
    public MatrixOps clear() {
        if (this == EMPTY) throw new IllegalStateException("MatrixOps.EMPTY est immuable");
        count = 0;
        return this;
    }

    /** Nombre d'opérations. */
    public int size() {
        return count;
    }

    /** Genre de l'opération {@code i} : {@link #TRANSLATE} ou {@link #ROTATE}. */
    public int kind(int i) {
        return (int) data[i * STRIDE];
    }

    /** Argument {@code arg} (0 à 3) de l'opération {@code i}. */
    public float arg(int i, int arg) {
        return data[i * STRIDE + 1 + arg];
    }

    private MatrixOps add(int kind, float a, float b, float c, float d) {
        if (this == EMPTY) throw new IllegalStateException("MatrixOps.EMPTY est immuable");
        int base = count * STRIDE;
        if (base + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
        data[base] = kind;
        data[base + 1] = a;
        data[base + 2] = b;
        data[base + 3] = c;
        data[base + 4] = d;
        count++;
        return this;
    }
}
