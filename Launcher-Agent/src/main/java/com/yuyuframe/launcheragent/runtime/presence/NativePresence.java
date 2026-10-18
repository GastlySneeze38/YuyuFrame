package com.yuyuframe.launcheragent.runtime.presence;

import com.yuyuframe.launcheragent.base.AgentFiles;
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
import java.util.UUID;

/**
 * « Ce joueur utilise-t-il YuyuFrame ? » — la question, posée au noyau natif
 * ({@code content_core.dll}, fonctions {@code yf_presence_*}, contrat dans
 * {@code content-core/src/presence/bridge.rs}).
 *
 * <p>Tout le travail est côté Rust : le jeton du compte (envoyé par le
 * launcher sur l'entrée standard du jeu et lu par le noyau, il ne passe
 * jamais par Java), les empreintes, la mémoire des
 * réponses, les requêtes groupées vers LauncherAPI sur un thread à lui. Ici il
 * n'y a que l'appel.
 *
 * <p>{@link #query} ne bloque jamais et peut être appelée depuis le fil du
 * jeu : elle rend ce que le noyau sait déjà. Un joueur inconnu répond
 * {@link #UNKNOWN} puis, quelques secondes plus tard, {@link #PRESENT} ou
 * {@link #ABSENT}.
 *
 * <p>Tout échec au chargement laisse {@link #available()} à faux : chaque
 * question répond alors {@link #UNKNOWN}, et personne n'est reconnu.
 */
public final class NativePresence {

    /** Même valeur que {@code ABI_VERSION} dans presence/bridge.rs. */
    private static final int ABI_VERSION = 2;

    /** On ne sait pas (encore) — ou pas de jeton : jeu lancé hors launcher, joueur sans compte. */
    public static final int UNKNOWN = 0;
    public static final int PRESENT = 1;
    /**
     * Aussi pour un faux joueur, jamais demandé — et pour un joueur d'un
     * serveur en mode hors ligne tant que {@link #context} n'a pas donné
     * l'adresse de ce serveur.
     */
    public static final int ABSENT = 2;

    private static MethodHandle start;
    private static MethodHandle status;
    private static MethodHandle context;

    private static final MethodHandle QUERY = load();

    private NativePresence() {
    }

    public static boolean available() {
        return QUERY != null;
    }

    private static MethodHandle load() {
        Path dll = AgentFiles.contentCore();
        if (dll == null || !Files.isRegularFile(dll)) {
            LauncherLog.warn("[PRESENCE] Indisponible : content_core.dll introuvable (" + dll + ")");
            return null;
        }
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
            Linker linker = Linker.nativeLinker();

            MethodHandle abi = linker.downcallHandle(lib.find("yf_presence_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[PRESENCE] Indisponible : content_core.dll annonce la version " + version
                    + " du contrat, " + ABI_VERSION + " attendue — aucun joueur ne sera reconnu");
                return null;
            }

            start = linker.downcallHandle(lib.find("yf_presence_start").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            // Courts, et ils lisent ou écrivent des tableaux Java : « critical ». Le seul
            // verrou pris côté natif n'est jamais tenu pendant une requête réseau.
            status = linker.downcallHandle(lib.find("yf_presence_status").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                Linker.Option.critical(true));
            context = linker.downcallHandle(lib.find("yf_presence_context").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
            return linker.downcallHandle(lib.find("yf_presence_query").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
        } catch (Throwable error) {
            LauncherLog.err("[PRESENCE] Noyau natif indisponible — aucun joueur ne sera reconnu", error);
            return null;
        }
    }

    /**
     * Démarre le thread de fond du noyau. Facultatif : la première question
     * le fait aussi.
     *
     * @return vrai si le noyau tourne (jeu lancé par le launcher) — ce qui ne
     *         dit pas qu'un jeton est déjà arrivé, voir {@link #status()}
     */
    public static boolean start() {
        if (QUERY == null) {
            return false;
        }
        try {
            return (int) start.invokeExact() == 1;
        } catch (Throwable error) {
            LauncherLog.err("[PRESENCE] Démarrage impossible", error);
            return false;
        }
    }

    /**
     * @param uuid l'UUID du PROFIL du joueur
     * @param name son pseudo de profil — jamais le texte affiché au-dessus de
     *             sa tête, que les serveurs décorent (grade, couleur)
     * @return {@link #UNKNOWN}, {@link #PRESENT} ou {@link #ABSENT}
     */
    public static int query(UUID uuid, String name) {
        if (QUERY == null || uuid == null || name == null || name.isEmpty()) {
            return UNKNOWN;
        }
        try {
            byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
            MemorySegment bytes = MemorySegment.ofArray(ascii);
            return (int) QUERY.invokeExact(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), bytes, ascii.length);
        } catch (Throwable error) {
            LauncherLog.err("[PRESENCE] Question impossible", error);
            return UNKNOWN;
        }
    }

    /**
     * Où l'on joue et sous quelle identité : ce qui permet de reconnaître les
     * joueurs d'un serveur en mode hors ligne, et de s'y annoncer. À rappeler
     * régulièrement ; le noyau ne réagit qu'à un changement.
     *
     * @param host l'adresse du serveur telle que le joueur l'a tapée,
     *             « hôte:port » — ou {@code null}
     * @param ip   l'adresse IP jointe, « ip:port » — ou {@code null}. Les deux
     *             à {@code null} : pas de serveur, ou serveurs hors ligne
     *             refusés par réglage — plus rien ne part pour eux
     * @param id   l'UUID du profil de notre joueur sur ce serveur, ou {@code null}
     * @param name son pseudo de profil, ou {@code null}
     */
    public static void context(String host, String ip, UUID id, String name) {
        if (QUERY == null) {
            return;
        }
        try {
            byte[] hostBytes = ascii(host);
            byte[] ipBytes = ascii(ip);
            byte[] nameBytes = id == null ? new byte[0] : ascii(name);
            long hi = id == null ? 0L : id.getMostSignificantBits();
            long lo = id == null ? 0L : id.getLeastSignificantBits();
            context.invokeExact(MemorySegment.ofArray(hostBytes), hostBytes.length, MemorySegment.ofArray(ipBytes), ipBytes.length,
                hi, lo, MemorySegment.ofArray(nameBytes), nameBytes.length);
        } catch (Throwable error) {
            LauncherLog.err("[PRESENCE] Contexte du serveur non transmis", error);
        }
    }

    private static byte[] ascii(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Pour le journal : {@code {état, joueurs en mémoire, reconnus, en attente}}.
     * État : 0 éteint, 1 en marche, 2 dernière requête en échec (nouvel essai
     * prévu), 3 quota de l'heure atteint, 4 jeton refusé (on attend le suivant),
     * 5 aucun jeton reçu du launcher.
     */
    public static int[] status() {
        int[] out = new int[4];
        if (QUERY == null) {
            return out;
        }
        try {
            MemorySegment segment = MemorySegment.ofArray(out);
            status.invokeExact(segment);
        } catch (Throwable error) {
            LauncherLog.err("[PRESENCE] État illisible", error);
        }
        return out;
    }
}
