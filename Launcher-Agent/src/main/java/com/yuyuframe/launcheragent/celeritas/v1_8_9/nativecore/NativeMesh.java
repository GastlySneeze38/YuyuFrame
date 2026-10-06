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
    private static final int ABI_VERSION = 1;

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

    private static final MethodHandle SECTION_SHAPE = load();

    private NativeMesh() {
    }

    public static boolean available() {
        return SECTION_SHAPE != null;
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
