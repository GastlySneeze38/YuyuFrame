package com.yuyuframe.launcheragent.runtime.ipc;

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
 * Le canal vers le launcher qui a lancé le jeu : un tube nommé à double sens,
 * tenu par le noyau natif ({@code content_core.dll}, fonctions
 * {@code yf_link_*}, contrat dans {@code content-core/src/link/bridge.rs} ;
 * le protocole est décrit côté launcher, {@code Backend/src/play/game_link.rs}).
 *
 * <p>Une question est une ligne de texte, ses champs séparés par des
 * tabulations ; la réponse aussi. Rien ici n'attend le launcher : on dépose
 * la question ({@link #send}), on relève la réponse plus tard ({@link #poll}),
 * typiquement à l'image suivante. Les deux peuvent être appelées depuis le fil
 * du jeu.
 *
 * <p>Le jeton du compte YuyuFrame passe par ce même tube mais ne remonte
 * jamais jusqu'ici : le noyau le garde pour lui.
 *
 * <p>Tout échec au chargement laisse {@link #available()} à faux : rien ne
 * part, et le jeu se comporte comme s'il avait été lancé sans launcher.
 */
public final class NativeLink {

    /** Même valeur que {@code ABI_VERSION} dans link/bridge.rs. */
    private static final int ABI_VERSION = 1;

    /** Une réponse fait au plus quelques kilo-octets (un jeton Minecraft). */
    private static final int MAX_REPLY = 64 * 1024;

    /** {@link #poll} : la réponse n'est pas encore là. */
    public static final String PENDING = null;

    private static MethodHandle send;
    private static MethodHandle notify;
    private static MethodHandle poll;

    private static final boolean READY = load();

    private NativeLink() {
    }

    /** Vrai si le jeu a un launcher au bout du fil (ou l'a eu : après une coupure, les questions répondent « erreur »). */
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

            MethodHandle abi = linker.downcallHandle(lib.find("yf_link_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[LINK] Indisponible : content_core.dll annonce la version " + version
                    + " du contrat, " + ABI_VERSION + " attendue");
                return false;
            }
            MethodHandle start = linker.downcallHandle(lib.find("yf_link_start").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            // Courts, et ils lisent ou écrivent des tableaux Java : « critical ». Le seul
            // verrou pris côté natif n'est jamais tenu pendant un échange avec le launcher.
            send = linker.downcallHandle(lib.find("yf_link_send").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
            notify = linker.downcallHandle(lib.find("yf_link_notify").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
            poll = linker.downcallHandle(lib.find("yf_link_poll").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
            // Ouvre le tube : faux = jeu lancé hors launcher.
            return (int) start.invokeExact() == 1;
        } catch (Throwable error) {
            LauncherLog.err("[LINK] Canal vers le launcher indisponible", error);
            return false;
        }
    }

    /**
     * Dépose une question.
     *
     * @return son numéro, à présenter à {@link #poll} — ou 0 si elle ne peut
     *         pas partir (pas de launcher, canal coupé)
     */
    public static long send(String question) {
        if (!READY || question == null) {
            return 0L;
        }
        try {
            byte[] bytes = question.getBytes(StandardCharsets.UTF_8);
            return (long) send.invokeExact(MemorySegment.ofArray(bytes), bytes.length);
        } catch (Throwable error) {
            LauncherLog.err("[LINK] Question non envoyée", error);
            return 0L;
        }
    }

    /** Dépose un message dont la réponse n'intéresse personne. Vrai s'il a été pris. */
    public static boolean notify(String message) {
        if (!READY || message == null) {
            return false;
        }
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            return (int) notify.invokeExact(MemorySegment.ofArray(bytes), bytes.length) == 1;
        } catch (Throwable error) {
            LauncherLog.err("[LINK] Message non envoyé", error);
            return false;
        }
    }

    /**
     * Relève la réponse à une question.
     *
     * @return {@link #PENDING} ({@code null}) tant qu'elle n'est pas là ;
     *         sinon la ligne de réponse. Un numéro inconnu ou une réponse
     *         illisible rendent une ligne {@code error⇥…}, pour que
     *         l'appelant n'attende jamais indéfiniment
     */
    public static String poll(long id) {
        if (!READY || id <= 0L) {
            return "error\tCanal indisponible";
        }
        try {
            byte[] out = new byte[MAX_REPLY];
            int written = (int) poll.invokeExact(id, MemorySegment.ofArray(out), out.length);
            if (written == -1) {
                return PENDING;
            }
            if (written < 0) {
                return "error\tRéponse perdue";
            }
            return new String(out, 0, written, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            LauncherLog.err("[LINK] Réponse illisible", error);
            return "error\tRéponse illisible";
        }
    }
}
