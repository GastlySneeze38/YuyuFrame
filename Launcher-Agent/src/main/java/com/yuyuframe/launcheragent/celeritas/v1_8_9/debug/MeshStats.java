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
 *   <li>un <b>contrôle</b> du noyau natif, provisoire : sur 500 sections, les
 *       quads sont écrits des deux façons — par le Java dans les vrais
 *       tampons, par le noyau à côté — et comparés octet par octet. Une ligne
 *       de bilan, puis plus rien. Les 300 premières sections sont laissées
 *       passer.</li>
 * </ul>
 */
public final class MeshStats {

    private static final int PROBE_SKIP = 300;
    private static final int PROBE_COUNT = 500;

    private static final LongAdder SECTIONS = new LongAdder();
    private static final LongAdder TASK_NANOS = new LongAdder();
    private static final LongAdder SHAPE_NANOS = new LongAdder();

    private static final AtomicInteger PROBE_TICKETS = new AtomicInteger();
    private static final LongAdder PROBE_SECTIONS = new LongAdder();
    private static final LongAdder PROBE_QUADS = new LongAdder();
    private static final LongAdder PROBE_SKIPPED = new LongAdder();
    private static final LongAdder PROBE_DIFFS = new LongAdder();
    private static final AtomicInteger DIFFS_LOGGED = new AtomicInteger();
    private static final AtomicBoolean SUMMARY_LOGGED = new AtomicBoolean();

    private MeshStats() {
    }

    public static void section(long taskNanos, long shapeNanos) {
        SECTIONS.increment();
        TASK_NANOS.add(taskNanos);
        SHAPE_NANOS.add(shapeNanos);
    }

    /** Vrai si cette section fait partie du contrôle : l'appelant écrit alors ses quads des deux façons. */
    public static boolean takeProbe() {
        if (PROBE_TICKETS.get() >= PROBE_SKIP + PROBE_COUNT) {
            return false;
        }
        int ticket = PROBE_TICKETS.getAndIncrement();
        return ticket >= PROBE_SKIP && ticket < PROBE_SKIP + PROBE_COUNT;
    }

    /** Une section du contrôle qui n'a pas pu être comparée (un repli vanilla a écrit dans les mêmes tampons). */
    public static void probeSkipped() {
        PROBE_SKIPPED.increment();
    }

    /**
     * Le résultat d'une section du contrôle.
     *
     * @param difference {@code null} si le noyau a écrit exactement ce qu'a écrit le Java, sinon où et quoi
     */
    public static void probe(int quads, String difference) {
        PROBE_SECTIONS.increment();
        PROBE_QUADS.add(quads);
        if (difference != null) {
            PROBE_DIFFS.increment();
            // Les trois premiers écarts en détail : assez pour chercher, sans noyer le log.
            if (DIFFS_LOGGED.getAndIncrement() < 3) {
                LauncherLog.err("[RENDERER] contrôle des quads natifs : ÉCART avec le Java — " + difference);
            }
        }
    }

    /** Le bilan du contrôle, une fois : toutes les sections prévues sont passées (une tâche annulée n'en rend pas). */
    private static void logProbeSummary() {
        if (PROBE_TICKETS.get() < PROBE_SKIP + PROBE_COUNT || !SUMMARY_LOGGED.compareAndSet(false, true)) {
            return;
        }
        LauncherLog.renderer(1, String.format(Locale.ROOT,
            "contrôle des quads natifs : %d sections comparées (%d quads), %d avec écart | %d non comparées (repli vanilla)",
            PROBE_SECTIONS.sum(), PROBE_QUADS.sum(), PROBE_DIFFS.sum(), PROBE_SKIPPED.sum()));
    }

    /** Pour la ligne de métriques : les sections maillées depuis l'appel précédent, ou {@code null} s'il n'y en a pas. */
    public static String drain(boolean nativeShape, boolean nativeQuads) {
        logProbeSummary();
        long sections = SECTIONS.sumThenReset();
        long taskNanos = TASK_NANOS.sumThenReset();
        long shapeNanos = SHAPE_NANOS.sumThenReset();
        if (sections == 0) {
            return null;
        }
        return String.format(Locale.ROOT, "Maillage: %d sections, %.0f µs/section (quads %s) dont forme %.1f µs (%s)",
            sections, taskNanos / 1000.0 / sections, nativeQuads ? "natifs" : "Java",
            shapeNanos / 1000.0 / sections, nativeShape ? "natif" : "Java");
    }
}
