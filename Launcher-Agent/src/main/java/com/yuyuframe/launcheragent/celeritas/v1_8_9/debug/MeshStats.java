package com.yuyuframe.launcheragent.celeritas.v1_8_9.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Les mesures du maillage, écrites par les fils de construction et lues par
 * la ligne de métriques du renderer (toutes les 5 secondes, dans le fichier
 * de log seulement). C'est la référence pour chaque optimisation : où part le
 * temps d'une section, et ce qu'elle contient.
 *
 * <p>Trois lignes :
 * <ul>
 *   <li>le temps moyen d'une section, découpé en ses étapes ;</li>
 *   <li>son contenu moyen (blocs, quads) et le temps cumulé des fils ;</li>
 *   <li>sur une section sur seize, le temps par bloc des questions posées au
 *       jeu — mesuré à part parce qu'il demande deux lectures d'horloge par bloc.</li>
 * </ul>
 */
public final class MeshStats {

    private static final int SAMPLE_EVERY = 16;

    private static final LongAdder SECTIONS = new LongAdder();
    private static final LongAdder TASK_NANOS = new LongAdder();
    private static final LongAdder LOOP_NANOS = new LongAdder();
    private static final LongAdder PREPARE_NANOS = new LongAdder();
    private static final LongAdder WRITE_NANOS = new LongAdder();
    private static final LongAdder ASSEMBLE_NANOS = new LongAdder();
    private static final LongAdder SHAPE_NANOS = new LongAdder();

    private static final LongAdder SOLID_BLOCKS = new LongAdder();
    private static final LongAdder MODEL_BLOCKS = new LongAdder();
    private static final LongAdder FLUID_BLOCKS = new LongAdder();
    private static final LongAdder COMMANDS = new LongAdder();
    /** Sections où il n'y avait rien à dessiner : elles coûtent quand même une tâche. */
    private static final LongAdder BLANK_SECTIONS = new LongAdder();

    private static final AtomicInteger SAMPLE_TICKETS = new AtomicInteger();
    private static final LongAdder SAMPLE_SECTIONS = new LongAdder();
    private static final LongAdder SAMPLE_BLOCKS = new LongAdder();
    private static final LongAdder SAMPLE_MODEL_NANOS = new LongAdder();
    private static final LongAdder SAMPLE_FACES_NANOS = new LongAdder();
    private static final LongAdder SAMPLE_TINT_NANOS = new LongAdder();

    private MeshStats() {
    }

    /**
     * Une section vient d'être maillée.
     *
     * @param loopNanos     la boucle sur les blocs : lecture de la copie, questions au jeu, rangement des commandes
     * @param prepareNanos  le noyau : copie des 27 sections dans son espace de travail
     * @param writeNanos    le noyau : lumière et écriture des sommets
     * @param assembleNanos l'assemblage des tampons en un maillage, tri du translucide compris
     * @param shapeNanos    le noyau : visibilité et boîtes occultantes
     * @param commands      quads et blocs de fluide confiés au noyau
     */
    public static void section(long taskNanos, long loopNanos, long prepareNanos, long writeNanos, long assembleNanos,
                               long shapeNanos, int solidBlocks, int modelBlocks, int fluidBlocks, int commands) {
        SECTIONS.increment();
        TASK_NANOS.add(taskNanos);
        LOOP_NANOS.add(loopNanos);
        PREPARE_NANOS.add(prepareNanos);
        WRITE_NANOS.add(writeNanos);
        ASSEMBLE_NANOS.add(assembleNanos);
        SHAPE_NANOS.add(shapeNanos);
        SOLID_BLOCKS.add(solidBlocks);
        MODEL_BLOCKS.add(modelBlocks);
        FLUID_BLOCKS.add(fluidBlocks);
        COMMANDS.add(commands);
        if (commands == 0) {
            BLANK_SECTIONS.increment();
        }
    }

    /** Vrai une fois sur seize : la section en cours mesure alors le détail par bloc. */
    public static boolean takeSample() {
        return SAMPLE_TICKETS.incrementAndGet() % SAMPLE_EVERY == 0;
    }

    /** Le détail d'une section de l'échantillon. */
    public static void sample(int modelBlocks, long modelNanos, long facesNanos, long tintNanos) {
        SAMPLE_SECTIONS.increment();
        SAMPLE_BLOCKS.add(modelBlocks);
        SAMPLE_MODEL_NANOS.add(modelNanos);
        SAMPLE_FACES_NANOS.add(facesNanos);
        SAMPLE_TINT_NANOS.add(tintNanos);
    }

    /** Pour la ligne de métriques : les sections maillées depuis l'appel précédent, ou {@code null} s'il n'y en a pas. */
    public static String drain(boolean nativeShape, boolean nativeQuads) {
        long sections = SECTIONS.sumThenReset();
        long taskNanos = TASK_NANOS.sumThenReset();
        long loopNanos = LOOP_NANOS.sumThenReset();
        long prepareNanos = PREPARE_NANOS.sumThenReset();
        long writeNanos = WRITE_NANOS.sumThenReset();
        long assembleNanos = ASSEMBLE_NANOS.sumThenReset();
        long shapeNanos = SHAPE_NANOS.sumThenReset();
        long solid = SOLID_BLOCKS.sumThenReset();
        long model = MODEL_BLOCKS.sumThenReset();
        long fluid = FLUID_BLOCKS.sumThenReset();
        long commands = COMMANDS.sumThenReset();
        long blank = BLANK_SECTIONS.sumThenReset();
        long sampleSections = SAMPLE_SECTIONS.sumThenReset();
        long sampleBlocks = SAMPLE_BLOCKS.sumThenReset();
        long sampleModel = SAMPLE_MODEL_NANOS.sumThenReset();
        long sampleFaces = SAMPLE_FACES_NANOS.sumThenReset();
        long sampleTint = SAMPLE_TINT_NANOS.sumThenReset();
        if (sections == 0) {
            return null;
        }

        double perSection = 1000.0 * sections;
        long other = taskNanos - loopNanos - prepareNanos - writeNanos - assembleNanos - shapeNanos;
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
            "Maillage: %d sections, %.0f µs/section (quads %s, forme %s) | boucle des blocs %.0f | noyau: copie %.0f, écriture %.0f, forme %.0f | assemblage %.0f | reste %.0f",
            sections, taskNanos / perSection, nativeQuads ? "natifs" : "Java", nativeShape ? "native" : "Java",
            loopNanos / perSection, prepareNanos / perSection, writeNanos / perSection, shapeNanos / perSection,
            assembleNanos / perSection, other / perSection));
        out.append(String.format(Locale.ROOT,
            " || contenu par section: %.0f blocs non vides, %.0f à modèle, %.0f de fluide, %.0f commandes | %d sections sans rien à dessiner | fils: %.0f ms cumulés",
            (double) solid / sections, (double) model / sections, (double) fluid / sections, (double) commands / sections,
            blank, taskNanos / 1.0e6));
        if (sampleBlocks > 0) {
            out.append(String.format(Locale.ROOT,
                " || par bloc à modèle (%d sections mesurées): modèle %.0f ns, faces %.0f ns, teinte %.0f ns",
                sampleSections, (double) sampleModel / sampleBlocks, (double) sampleFaces / sampleBlocks,
                (double) sampleTint / sampleBlocks));
        }
        return out.toString();
    }
}
