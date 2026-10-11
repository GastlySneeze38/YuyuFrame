package com.yuyuframe.launcheragent.runtime.share;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Le partage des réglages par un lien, fait par le noyau natif
 * ({@code content_core.dll}, fonctions {@code yf_share_*}, contrat dans
 * {@code content-core/src/share/bridge.rs}).
 *
 * <p>Le noyau fabrique et relit le même lien que le launcher
 * ({@code yuyuframe://options/…} : compression zstd sur un dictionnaire figé,
 * puis base 32 768), écarte ce qui ne doit pas voyager (mots de passe des
 * connexions automatiques, macros, langue) et parle lui-même au presse-papiers
 * du système — le jeu y accède par une bibliothèque différente à chaque
 * version. Java ne lui donne et n'en reçoit que du texte.
 *
 * <p>Tout échec au chargement laisse {@link #available()} à faux.
 */
public final class NativeShare {

    /** Même valeur que {@code ABI_VERSION} dans share/bridge.rs. */
    private static final int ABI_VERSION = 2;

    /** Place pour un résultat (réglages relus, lien, presse-papiers) ou un message d'erreur. */
    private static final int OUT_BYTES = 1024 * 1024;

    /** Ce qu'une opération a donné : son résultat, ou le message à montrer. */
    public static final class Outcome {
        public final boolean ok;
        public final String text;

        private Outcome(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }

        private static Outcome failure(String message) {
            return new Outcome(false, message);
        }
    }

    private static MethodHandle link;
    private static MethodHandle settings;
    private static MethodHandle clipSet;
    private static MethodHandle clipGet;

    private static final boolean READY = load();

    private NativeShare() {
    }

    public static boolean available() {
        return READY;
    }

    private static boolean load() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return false;
        }
        Path dll = Path.of(appData, "YuyuFrame", "agent", "content_core.dll");
        if (!Files.isRegularFile(dll)) {
            return false;
        }
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
            Linker linker = Linker.nativeLinker();
            MethodHandle abi = linker.downcallHandle(lib.find("yf_share_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[SHARE] Indisponible : content_core.dll annonce la version " + version
                    + " du contrat, " + ABI_VERSION + " attendue");
                return false;
            }
            // Pas « critical » : ces appels compressent ou attendent le presse-papiers,
            // ce n'est pas l'affaire de quelques microsecondes. Mémoire native, donc.
            FunctionDescriptor textToText = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            link = linker.downcallHandle(lib.find("yf_share_link").orElseThrow(), textToText);
            settings = linker.downcallHandle(lib.find("yf_share_settings").orElseThrow(), textToText);
            clipSet = linker.downcallHandle(lib.find("yf_share_clip_set").orElseThrow(), textToText);
            clipGet = linker.downcallHandle(lib.find("yf_share_clip_get").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            return true;
        } catch (Throwable error) {
            LauncherLog.err("[SHARE] Partage des réglages indisponible", error);
            return false;
        }
    }

    /**
     * Le lien de ces réglages.
     *
     * @param lines une ligne {@code clé=valeur} par réglage
     * @return le lien ; s'il est en plusieurs parties, une ligne vide les sépare
     */
    public static Outcome link(String lines) {
        return call(link, lines, "Partage impossible");
    }

    /** Les réglages que porte un lien collé, en lignes {@code clé=valeur}, déjà filtrés. */
    public static Outcome settings(String pasted) {
        return call(settings, pasted, "Lecture du lien impossible");
    }

    /** Pose un texte dans le presse-papiers du système. */
    public static Outcome copy(String text) {
        return call(clipSet, text, "Copie impossible");
    }

    /** Le texte du presse-papiers du système. */
    public static Outcome clipboard() {
        if (!READY) {
            return Outcome.failure("Partage indisponible");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(OUT_BYTES);
            return outcome(out, (int) clipGet.invokeExact(out, OUT_BYTES), "Presse-papiers illisible");
        } catch (Throwable error) {
            LauncherLog.err("[SHARE] Presse-papiers illisible", error);
            return Outcome.failure("Presse-papiers illisible");
        }
    }

    private static Outcome call(MethodHandle function, String input, String failure) {
        if (!READY) {
            return Outcome.failure("Partage indisponible");
        }
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = (input == null ? "" : input).getBytes(StandardCharsets.UTF_8);
            MemorySegment in = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, in, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment out = arena.allocate(OUT_BYTES);
            return outcome(out, (int) function.invokeExact(in, bytes.length, out, OUT_BYTES), failure);
        } catch (Throwable error) {
            LauncherLog.err("[SHARE] " + failure, error);
            return Outcome.failure(failure);
        }
    }

    /** Positif ou nul : le résultat, de cette longueur. Négatif : un message, de la longueur opposée. */
    private static Outcome outcome(MemorySegment out, int code, String failure) {
        if (code == Integer.MIN_VALUE) {
            return Outcome.failure(failure);
        }
        byte[] bytes = new byte[Math.abs(code)];
        MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return new Outcome(code >= 0, new String(bytes, StandardCharsets.UTF_8));
    }
}
