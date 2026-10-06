package com.yuyuframe.launcheragent.celeritas.nativecore;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.PartitionTree;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.SortState;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Le tri du translucide fait par le noyau natif ({@code content_core.dll},
 * fonctions {@code yf_sort_*}, contrat dans {@code content-core/src/sort/bridge.rs})
 * — notre code, pas celui de Celeritas. C'est un port de
 * {@code chunk/sorting} ({@code TranslucentSorter}, {@code PartitionTree}),
 * qui reste là comme repli et comme référence.
 *
 * <p>Le noyau ne garde rien : un arbre de découpe sort « à plat » dans un
 * tableau d'entiers que {@link PartitionTree} garde et retend à chaque
 * relecture.
 *
 * <p>Tout échec au chargement laisse {@link #available()} à faux : le tri
 * Java sert alors, avec le même résultat.
 */
public final class NativeSort {

    /** Même valeur que {@code ABI_VERSION} dans sort/bridge.rs. */
    private static final int ABI_VERSION = 1;

    /** {@code -Dyuyuframe.renderer.nativeSort=false} garde le tri Java (pour comparer). */
    private static final String PROPERTY = "yuyuframe.renderer.nativeSort";

    private static final int HEADER = 6;
    private static final int KIND_STATIC = 1;
    private static final int KIND_TREE = 2;
    private static final int KIND_DYNAMIC = 3;

    private static MethodHandle take;
    private static MethodHandle treeOrder;
    private static MethodHandle distanceOrder;

    private static final MethodHandle ANALYZE = load();

    /** L'en-tête d'une analyse, hors du tas Java : l'analyse peut durer, elle n'est pas appelée en « critical ». */
    private static final ThreadLocal<MemorySegment> HEADER_SEGMENT =
        ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(ValueLayout.JAVA_INT, HEADER));

    private NativeSort() {
    }

    public static boolean available() {
        return ANALYZE != null;
    }

    private static MethodHandle load() {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
            LauncherLog.renderer(2, "Tri natif du translucide désactivé (" + PROPERTY + "=false)");
            return null;
        }
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }
        Path dll = Path.of(appData, "YuyuFrame", "agent", "content_core.dll");
        if (!Files.isRegularFile(dll)) {
            return null;
        }
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
            Linker linker = Linker.nativeLinker();

            MethodHandle abi = linker.downcallHandle(lib.find("yf_sort_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[RENDERER] Tri natif du translucide indisponible : content_core.dll annonce la version "
                    + version + " du contrat, " + ABI_VERSION + " attendue — tri Java gardé");
                return null;
            }

            // Pas « critical » : sur une grosse section, l'analyse peut prendre des
            // millisecondes, et un appel critical retient le ramasse-miettes tout ce temps.
            MethodHandle analyze = linker.downcallHandle(lib.find("yf_sort_analyze").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // Les trois autres sont courts et lisent ou écrivent des tableaux Java.
            take = linker.downcallHandle(lib.find("yf_sort_take").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                Linker.Option.critical(true));
            treeOrder = linker.downcallHandle(lib.find("yf_sort_tree_order").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
            distanceOrder = linker.downcallHandle(lib.find("yf_sort_distance_order").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));

            LauncherLog.renderer(2, "Tri natif du translucide chargé");
            return analyze;
        } catch (Throwable error) {
            LauncherLog.err("[RENDERER] Tri natif du translucide indisponible — tri Java gardé", error);
            return null;
        }
    }

    /**
     * Analyse les quads translucides d'une section et choisit leur tri.
     *
     * @param vertices  l'adresse des sommets, quatre par quad, la position en trois flottants au début de chacun
     * @param stride    octets d'un sommet au suivant
     * @return l'état de tri, ou {@code null} si le noyau a échoué (à l'appelant de trier en Java)
     */
    public static SortState analyze(long vertices, int quadCount, int stride) {
        try {
            MemorySegment header = HEADER_SEGMENT.get();
            int kind = (int) ANALYZE.invokeExact(MemorySegment.ofAddress(vertices), quadCount, stride, header);
            if (kind < 0) {
                return null;
            }
            int[] ints = new int[header.getAtIndex(ValueLayout.JAVA_INT, 1)];
            float[] floats = new float[header.getAtIndex(ValueLayout.JAVA_INT, 2)];
            // Dans des variables : javac type en Object une expression « ? : » passée
            // telle quelle à invokeExact, et l'appel est alors refusé à l'exécution.
            MemorySegment intsOut = ints.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(ints);
            MemorySegment floatsOut = floats.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(floats);
            take.invokeExact(intsOut, floatsOut);

            switch (kind) {
                case KIND_STATIC:
                    return new SortState.Static(ints);
                case KIND_TREE:
                    float[][] cutPlanes = new float[3][];
                    int from = 0;
                    for (int axis = 0; axis < 3; axis++) {
                        int count = header.getAtIndex(ValueLayout.JAVA_INT, 3 + axis);
                        cutPlanes[axis] = Arrays.copyOfRange(floats, from, from + count);
                        from += count;
                    }
                    return PartitionTree.fromNative(ints, quadCount, cutPlanes);
                case KIND_DYNAMIC:
                    return new SortState.Dynamic(floats);
                default:
                    return SortState.NONE;
            }
        } catch (Throwable error) {
            // Une fois : la même erreur reviendrait à chaque section.
            if (ANALYZE_FAILURE_LOGGED.compareAndSet(false, true)) {
                LauncherLog.err("[RENDERER] exception à l'analyse native du translucide — tri Java pour cette section", error);
            }
            return null;
        }
    }

    private static final AtomicBoolean ANALYZE_FAILURE_LOGGED = new AtomicBoolean();

    /**
     * Relit un arbre pour une position de caméra et écrit l'ordre de dessin à l'adresse donnée.
     *
     * @param elements 6 = deux triangles par quad, 4 = le quad tel quel
     * @return vrai si exactement {@code quadCount} quads ont été écrits
     */
    public static boolean treeOrder(int[] tree, float camX, float camY, float camZ, long out, int quadCount, int elements) {
        try {
            return (int) treeOrder.invokeExact(MemorySegment.ofArray(tree), tree.length, camX, camY, camZ,
                MemorySegment.ofAddress(out), quadCount, elements) == quadCount;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Comme {@link #treeOrder(int[], float, float, float, long, int, int)}, mais rend les numéros des quads. */
    public static int[] treeOrder(int[] tree, float camX, float camY, float camZ, int quadCount) {
        int[] order = new int[quadCount];
        try {
            int written = (int) treeOrder.invokeExact(MemorySegment.ofArray(tree), tree.length, camX, camY, camZ,
                MemorySegment.ofArray(order), quadCount, 1);
            return written == quadCount ? order : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** Trie des quads par distance à la caméra, le plus loin d'abord, et écrit l'ordre de dessin à l'adresse donnée. */
    public static boolean distanceOrder(float[] centers, float camX, float camY, float camZ, long out, int elements) {
        try {
            return (int) distanceOrder.invokeExact(MemorySegment.ofArray(centers), centers.length / 3, camX, camY, camZ,
                MemorySegment.ofAddress(out), elements) == 0;
        } catch (Throwable error) {
            return false;
        }
    }

}
