package com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Le noyau natif du maillage, dans {@code content_core.dll}, appelé par FFM
 * depuis les fils de construction — notre code, pas celui de Celeritas. Le
 * contrat des fonctions {@code yf_mesh_*} est dans
 * {@code content-core/src/mesh/bridge.rs}.
 *
 * <p>Il ne lit que des tableaux de nombres : Java lui tend ses propres
 * tableaux (option {@code critical} de FFM, sans copie), tels que
 * {@code SectionSnapshot} les range.
 *
 * <p>Tout échec au chargement (DLL absente ou trop ancienne, accès natif
 * refusé) laisse {@link #available()} à faux : l'appelant garde alors le
 * calcul Java, qui donne le même résultat.
 */
public final class NativeMesh {

    /** Même valeur que {@code ABI_VERSION} dans mesh/bridge.rs. */
    private static final int ABI_VERSION = 2;

    /** {@code -Dyuyuframe.renderer.nativeMesh=false} garde le calcul Java (pour comparer). */
    private static final String PROPERTY = "yuyuframe.renderer.nativeMesh";

    /** Cases de la sortie avant les boîtes : visibilité (deux moitiés), volume à dessiner. */
    private static final int HEADER = 3;
    /** Les boîtes sont disjointes et font au moins 8 blocs : jamais plus de 512 dans une section. */
    public static final int SHAPE_OUT_LENGTH = HEADER + 512;

    /** La section n'a rien à dessiner ni à occulter. */
    public static final int SHAPE_NO_DATA = -1;
    /** Le noyau a échoué : la sortie n'est pas à lire. */
    public static final int SHAPE_FAILED = -2;

    // Remplis par load() ; tous nuls si le noyau n'est pas disponible.
    private static MethodHandle begin;
    private static MethodHandle loadSection;
    private static MethodHandle emitQuads;
    private static int workspaceBytes;

    private static final MethodHandle SECTION_SHAPE = load();

    /** Passe à vrai si le noyau échoue une fois sur l'écriture des quads : le Java reprend pour de bon. */
    private static volatile boolean quadsBroken;

    private NativeMesh() {
    }

    public static boolean available() {
        return SECTION_SHAPE != null;
    }

    /** Vrai si le noyau écrit les quads (lumière et sommets) à la place du Java. */
    public static boolean quadsAvailable() {
        return SECTION_SHAPE != null && !quadsBroken;
    }

    public static void disableQuads() {
        quadsBroken = true;
    }

    private static MethodHandle load() {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
            LauncherLog.renderer(2, "Noyau natif du maillage désactivé (" + PROPERTY + "=false)");
            return null;
        }
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }
        // Même emplacement que ContentBridge (JNI) et NativeMouse (FFM).
        Path dll = Path.of(appData, "YuyuFrame", "agent", "content_core.dll");
        if (!Files.isRegularFile(dll)) {
            LauncherLog.warn("[RENDERER] Noyau natif du maillage indisponible : " + dll + " absente — calcul Java gardé");
            return null;
        }
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
            Linker linker = Linker.nativeLinker();

            MethodHandle abi = linker.downcallHandle(lib.find("yf_mesh_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[RENDERER] Noyau natif du maillage indisponible : content_core.dll annonce la version "
                    + version + " du contrat, " + ABI_VERSION + " attendue — calcul Java gardé");
                return null;
            }

            // critical(true) : la fonction ne prend aucun verrou et ne rappelle pas
            // Java, et elle lit et écrit directement dans des tableaux Java.
            MethodHandle shape = linker.downcallHandle(lib.find("yf_mesh_section_shape").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));

            MethodHandle size = linker.downcallHandle(lib.find("yf_mesh_workspace_bytes").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            workspaceBytes = (int) size.invokeExact();
            begin = linker.downcallHandle(lib.find("yf_mesh_begin").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.critical(false));
            loadSection = linker.downcallHandle(lib.find("yf_mesh_load_section").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                Linker.Option.critical(true));
            emitQuads = linker.downcallHandle(lib.find("yf_mesh_emit_quads").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT),
                Linker.Option.critical(true));

            LauncherLog.renderer(2, "Noyau natif du maillage chargé");
            return shape;
        } catch (Throwable error) {
            // Symbole absent (DLL d'avant le noyau), accès natif refusé…
            LauncherLog.err("[RENDERER] Noyau natif du maillage indisponible — calcul Java gardé", error);
            return null;
        }
    }

    /**
     * La forme d'une section : visibilité à travers elle, volume à dessiner,
     * boîtes occultantes.
     *
     * @param blocks les 4 096 états de la section ({@code SectionSnapshot.blocks})
     * @param flags  65 536 octets, un par état : bit 0 = se dessine, bit 1 = cube plein opaque
     * @param out    {@link #SHAPE_OUT_LENGTH} entiers, remplis par l'appel — voir {@link #visibility} et {@link #occluderData}
     * @return le nombre de boîtes, {@link #SHAPE_NO_DATA} ou {@link #SHAPE_FAILED}
     */
    public static int sectionShape(char[] blocks, MemorySegment flags, int[] out) {
        try {
            return (int) SECTION_SHAPE.invokeExact(MemorySegment.ofArray(blocks), flags, MemorySegment.ofArray(out), out.length);
        } catch (Throwable error) {
            return SHAPE_FAILED;
        }
    }

    // ── Écriture des quads ───────────────────────────────────────────────────
    //
    // Une section : begin, puis loadSection pour chacune des 27 copies, puis
    // emitQuads. Tout depuis le même fil, avec son propre espace de travail.

    /** Octets d'un sommet écrit par le noyau : le format {@code VanillaLikeChunkVertex}. */
    public static final int VERTEX_BYTES = 32;
    /** Tampons de destination au plus par appel. */
    public static final int MAX_BUCKETS = 32;

    /** La mémoire de travail d'un fil de construction ; rendue au système quand plus rien ne la tient. */
    public static MemorySegment allocateWorkspace() {
        return Arena.ofAuto().allocate(workspaceBytes, 8);
    }

    /** Commence une section, dont ({@code baseX}, {@code baseY}, {@code baseZ}) est le coin en blocs du monde. */
    public static void begin(MemorySegment workspace, int baseX, int baseY, int baseZ, boolean hasSkyLight) throws Throwable {
        begin.invokeExact(workspace, baseX, baseY, baseZ, hasSkyLight ? 1 : 0);
    }

    /**
     * Tend une des 27 sections copiées, par son rang dans {@code ChunkRenderContext.sections()}.
     * Les quatre tableaux sont ceux de {@code SectionSnapshot} ; un tableau absent se passe en {@link MemorySegment#NULL}.
     */
    public static void loadSection(MemorySegment workspace, int slot, MemorySegment blocks, MemorySegment blockLight,
                                   MemorySegment skyLight, MemorySegment heights) throws Throwable {
        loadSection.invokeExact(workspace, slot, blocks, blockLight, skyLight, heights);
    }

    /**
     * Calcule la lumière des quads commandés et écrit leurs sommets dans les tampons de destination.
     *
     * @param properties   {@code BlockFlags189.properties()}
     * @param commands     quatre entiers par quad — voir {@code Command} dans mesh/emit.rs
     * @param destinations une adresse par tampon, 0 pour un tampon sans quad
     * @return vrai si tout est écrit
     */
    public static boolean emitQuads(MemorySegment workspace, MemorySegment properties, int[] commands, int commandCount,
                                    long[] destinations, int bucketCount, int sectionIndex) throws Throwable {
        int result = (int) emitQuads.invokeExact(workspace, properties, MemorySegment.ofArray(commands), commandCount,
            MemorySegment.ofArray(destinations), bucketCount, sectionIndex);
        return result == 0;
    }

    /** La visibilité écrite par {@link #sectionShape}, au format {@code VisibilityEncoding}. */
    public static long visibility(int[] out) {
        return (out[0] & 0xFFFFFFFFL) | (long) out[1] << 32;
    }

    /**
     * Les données d'occlusion écrites par {@link #sectionShape}, au format de
     * {@code BuiltRenderSectionData.occluderBoxes} : le volume à dessiner, puis les boîtes.
     */
    public static int[] occluderData(int[] out, int boxCount) {
        int[] data = new int[boxCount + 1];
        data[0] = out[2];
        System.arraycopy(out, HEADER, data, 1, boxCount);
        return data;
    }
}
