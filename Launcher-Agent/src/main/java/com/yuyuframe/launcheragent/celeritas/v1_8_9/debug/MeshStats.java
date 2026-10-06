package com.yuyuframe.launcheragent.celeritas.v1_8_9.debug;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Les mesures du maillage, écrites par les fils de construction et lues par
 * la ligne de métriques du renderer (toutes les 5 secondes, dans le fichier
 * de log seulement) : le temps moyen d'une section, et dedans ce que prend le
 * noyau natif. C'est la référence pour mesurer chaque optimisation.
 */
public final class MeshStats {

    private static final LongAdder SECTIONS = new LongAdder();
    private static final LongAdder TASK_NANOS = new LongAdder();
    private static final LongAdder SHAPE_NANOS = new LongAdder();
    private static final LongAdder QUAD_NANOS = new LongAdder();

    private MeshStats() {
    }

    /**
     * @param quadNanos le temps passé dans le noyau pour les quads (copie des sections, lumière,
     *                  écriture des sommets) ; le reste de la tâche est du Java, surtout des appels au jeu
     */
    public static void section(long taskNanos, long shapeNanos, long quadNanos) {
        SECTIONS.increment();
        TASK_NANOS.add(taskNanos);
        SHAPE_NANOS.add(shapeNanos);
        QUAD_NANOS.add(quadNanos);
    }

    /** Pour la ligne de métriques : les sections maillées depuis l'appel précédent, ou {@code null} s'il n'y en a pas. */
    public static String drain(boolean nativeShape, boolean nativeQuads) {
        long sections = SECTIONS.sumThenReset();
        long taskNanos = TASK_NANOS.sumThenReset();
        long shapeNanos = SHAPE_NANOS.sumThenReset();
        long quadNanos = QUAD_NANOS.sumThenReset();
        if (sections == 0) {
            return null;
        }
        return String.format(Locale.ROOT,
            "Maillage: %d sections, %.0f µs/section (quads %s) dont noyau des quads %.1f µs, forme %.1f µs (%s)",
            sections, taskNanos / 1000.0 / sections, nativeQuads ? "natifs" : "Java",
            quadNanos / 1000.0 / sections, shapeNanos / 1000.0 / sections, nativeShape ? "natif" : "Java");
    }
}
