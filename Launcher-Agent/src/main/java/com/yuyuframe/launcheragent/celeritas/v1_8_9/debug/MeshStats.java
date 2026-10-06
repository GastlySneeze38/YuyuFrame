package com.yuyuframe.launcheragent.celeritas.v1_8_9.debug;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Les mesures du maillage, écrites par les fils de construction et lues par
 * la ligne de métriques du renderer (toutes les 5 secondes, dans le fichier
 * de log seulement).
 *
 * <p>Deux choses :
 * <ul>
 *   <li>le temps moyen d'une section, et dedans celui du calcul de sa forme —
 *       la référence pour mesurer chaque pièce portée dans le noyau natif ;</li>
 *   <li>un <b>contrôle</b> du noyau natif, provisoire : sur 500 sections, la
 *       forme est calculée des deux façons, comparée et chronométrée. Une
 *       ligne de bilan, puis plus rien. Les 300 premières sections sont
 *       laissées passer : le Java n'y est pas encore compilé à chaud.</li>
 * </ul>
 */
public final class MeshStats {

    private static final int PROBE_SKIP = 300;
    private static final int PROBE_COUNT = 500;

    private static final LongAdder SECTIONS = new LongAdder();
    private static final LongAdder TASK_NANOS = new LongAdder();
    private static final LongAdder SHAPE_NANOS = new LongAdder();

    private static final AtomicInteger PROBE_TICKETS = new AtomicInteger();
    private static final AtomicInteger PROBE_DONE = new AtomicInteger();
    private static final LongAdder PROBE_JAVA_NANOS = new LongAdder();
    private static final LongAdder PROBE_NATIVE_NANOS = new LongAdder();
    private static final LongAdder PROBE_JAVA_BOXES = new LongAdder();
    private static final LongAdder PROBE_NATIVE_BOXES = new LongAdder();
    private static final LongAdder PROBE_VISIBILITY_DIFFS = new LongAdder();
    private static final LongAdder PROBE_BOUNDS_DIFFS = new LongAdder();
    private static final AtomicBoolean DIFF_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SUMMARY_LOGGED = new AtomicBoolean();

    private MeshStats() {
    }

    public static void section(long taskNanos, long shapeNanos) {
        SECTIONS.increment();
        TASK_NANOS.add(taskNanos);
        SHAPE_NANOS.add(shapeNanos);
    }

    /** Vrai si cette section fait partie du contrôle : l'appelant calcule alors aussi la forme en Java. */
    public static boolean takeProbe() {
        if (PROBE_TICKETS.get() >= PROBE_SKIP + PROBE_COUNT) {
            return false;
        }
        int ticket = PROBE_TICKETS.getAndIncrement();
        return ticket >= PROBE_SKIP && ticket < PROBE_SKIP + PROBE_COUNT;
    }

    public static void probe(long javaNanos, long nativeNanos, long javaVisibility, long nativeVisibility,
                             int[] javaData, int[] nativeData) {
        PROBE_JAVA_NANOS.add(javaNanos);
        PROBE_NATIVE_NANOS.add(nativeNanos);
        PROBE_JAVA_BOXES.add(javaData == null ? 0 : javaData.length - 1);
        PROBE_NATIVE_BOXES.add(nativeData == null ? 0 : nativeData.length - 1);

        boolean visibilityDiffers = javaVisibility != nativeVisibility;
        // Le volume à dessiner doit être le même ; les boîtes, elles, peuvent différer à volume égal.
        boolean boundsDiffer = (javaData == null) != (nativeData == null)
            || (javaData != null && javaData[0] != nativeData[0]);
        if (visibilityDiffers) {
            PROBE_VISIBILITY_DIFFS.increment();
        }
        if (boundsDiffer) {
            PROBE_BOUNDS_DIFFS.increment();
        }
        if ((visibilityDiffers || boundsDiffer) && DIFF_LOGGED.compareAndSet(false, true)) {
            LauncherLog.err("[RENDERER] contrôle du noyau natif : ÉCART avec le Java — visibilité "
                + Long.toHexString(javaVisibility) + " (Java) / " + Long.toHexString(nativeVisibility) + " (natif), volume "
                + (javaData == null ? "aucun" : Integer.toHexString(javaData[0])) + " (Java) / "
                + (nativeData == null ? "aucun" : Integer.toHexString(nativeData[0])) + " (natif)");
        }

        PROBE_DONE.incrementAndGet();
    }

    /** Le bilan du contrôle, une fois : toutes les sections prévues sont passées (une tâche annulée n'en rend pas). */
    private static void logProbeSummary() {
        int done = PROBE_DONE.get();
        if (PROBE_TICKETS.get() < PROBE_SKIP + PROBE_COUNT || done == 0 || !SUMMARY_LOGGED.compareAndSet(false, true)) {
            return;
        }
        LauncherLog.renderer(1, String.format(Locale.ROOT,
            "contrôle du noyau natif sur %d sections : forme Java %.1f µs, natif %.1f µs | écarts de visibilité %d, de volume %d | boîtes Java %d, natif %d",
            done, PROBE_JAVA_NANOS.sum() / 1000.0 / done, PROBE_NATIVE_NANOS.sum() / 1000.0 / done,
            PROBE_VISIBILITY_DIFFS.sum(), PROBE_BOUNDS_DIFFS.sum(), PROBE_JAVA_BOXES.sum(), PROBE_NATIVE_BOXES.sum()));
    }

    /** Pour la ligne de métriques : les sections maillées depuis l'appel précédent, ou {@code null} s'il n'y en a pas. */
    public static String drain(boolean nativeShape) {
        logProbeSummary();
        long sections = SECTIONS.sumThenReset();
        long taskNanos = TASK_NANOS.sumThenReset();
        long shapeNanos = SHAPE_NANOS.sumThenReset();
        if (sections == 0) {
            return null;
        }
        return String.format(Locale.ROOT, "Maillage: %d sections, %.0f µs/section dont forme %.1f µs (%s)",
            sections, taskNanos / 1000.0 / sections, shapeNanos / 1000.0 / sections, nativeShape ? "natif" : "Java");
    }
}
