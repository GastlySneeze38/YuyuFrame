package com.yuyuframe.launcheragent.runtime.combat;

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
 * Le combat, transmis au noyau natif ({@code content_core.dll}, fonctions
 * {@code yf_combat_*}, contrat dans {@code content-core/src/combat/bridge.rs}).
 *
 * <p>Java ne fait que relever et envoyer. Ce qui vaut d'être gardé, quand un
 * combat commence, où et comment le journal s'écrit : tout cela se décide
 * côté Rust (docs/scoring/). Ici il n'y a que les appels.
 *
 * <p>Aucun de ces appels n'attend : le noyau recopie les arguments et les
 * dépose dans la file de son fil d'écriture. Ils sont donc sûrs depuis le fil
 * du jeu, à chaque tick.
 *
 * <p>Tout échec au chargement laisse {@link #available()} à faux : chaque
 * appel ne fait alors rien, et aucun journal n'est écrit.
 */
public final class NativeCombat {

    /** Même valeur que {@code ABI_VERSION} dans combat/bridge.rs. */
    private static final int ABI_VERSION = 7;

    /** Place réservée au texte de l'historique : deux cents duels en tiennent bien moins. */
    private static final int HISTORY_BYTES = 256 * 1024;

    /**
     * Le résumé d'un duel, en nombres — {@code Report::numbers} dans
     * combat/score/mod.rs, même ordre.
     */
    public static final int REPORT_LEN = 35;
    /** Note sur 100, ou −1 si le duel n'est pas noté. */
    public static final int REPORT_SCORE = 0;
    /** De 0 à 1. */
    public static final int REPORT_CONFIDENCE = 1;
    /** 0 aucun, 1 victoire, 2 défaite, 3 nul, 4 inconnu. */
    public static final int REPORT_RESULT = 2;
    /** Pourquoi il n'y a pas de note : 0 il y en a une, 1 duel trop court, 2 trop peu d'opportunités. */
    public static final int REPORT_REASON = 3;
    /** Les six familles sur 100, à la suite : conversion, qualité du smash, exposition, contrôle vertical, ressources, niveau de l'adversaire. */
    public static final int REPORT_FAMILIES = 4;
    public static final int FAMILY_COUNT = 6;
    /** En millisecondes, ou −1 s'il est inconnu. */
    public static final int REPORT_PING = 10;
    public static final int REPORT_MY_OPENED = 11;
    public static final int REPORT_MY_CONVERTED = 12;
    public static final int REPORT_THEIR_OPENED = 13;
    public static final int REPORT_THEIR_CONVERTED = 14;
    /** Secondes passées avec l'adversaire à portée. */
    public static final int REPORT_ENGAGED_SECS = 15;
    /** Les quatre familles du niveau de l'adversaire sur 100 : conversion, qualité du smash, défense, contrôle vertical. */
    public static final int REPORT_FOE_FAMILIES = 16;
    public static final int FOE_FAMILY_COUNT = 4;
    /** Nombre d'événements derrière chacune de mes cinq familles (dans l'ordre de {@link #REPORT_FAMILIES}). */
    public static final int REPORT_MY_COUNTS = 20;
    /** Nombre d'événements derrière chacune des quatre familles de l'adversaire. */
    public static final int REPORT_FOE_COUNTS = 25;
    /** Bonus de smash : les miens portés puis possibles, les siens portés puis possibles (points de dégâts). */
    public static final int REPORT_MY_SMASH_DEALT = 29;
    public static final int REPORT_MY_SMASH_POSSIBLE = 30;
    public static final int REPORT_THEIR_SMASH_DEALT = 31;
    public static final int REPORT_THEIR_SMASH_POSSIBLE = 32;
    /** Charge d'attaque moyenne à mes smashs, de 0 à 1 ; −1 sans smash. */
    public static final int REPORT_SMASH_CHARGE = 33;
    /** Mon jeu seul sur 100 : mes cinq familles combinées, sans le niveau de l'adversaire. */
    public static final int REPORT_OWN = 34;

    /** Nombres décrivant notre joueur à un tick — {@code ME_LEN} dans combat/record.rs, même ordre. */
    public static final int ME_LEN = 15;
    public static final int ME_ID = 0;
    public static final int ME_X = 1;
    public static final int ME_Y = 2;
    public static final int ME_Z = 3;
    public static final int ME_VX = 4;
    public static final int ME_VY = 5;
    public static final int ME_VZ = 6;
    public static final int ME_YAW = 7;
    public static final int ME_PITCH = 8;
    public static final int ME_HEALTH = 9;
    public static final int ME_MAX_HEALTH = 10;
    public static final int ME_FALL = 11;
    public static final int ME_ATTACK = 12;
    public static final int ME_FLAGS = 13;
    public static final int ME_PING = 14;

    /** Nombres décrivant un autre joueur à un tick — {@code OTHER_LEN} dans combat/record.rs : identifiant, position, vitesse, drapeaux. */
    public static final int OTHER_LEN = 8;
    /** Autres joueurs transmis par tick, au plus — {@code MAX_OTHERS} dans combat/bridge.rs. */
    public static final int MAX_OTHERS = 16;

    public static final int FLAG_ON_GROUND = 1;
    public static final int FLAG_SPRINTING = 1 << 1;
    public static final int FLAG_GLIDING = 1 << 2;
    public static final int FLAG_IN_WATER = 1 << 3;
    public static final int FLAG_USING_ITEM = 1 << 4;
    public static final int FLAG_SPECTATOR = 1 << 5;
    public static final int FLAG_CREATIVE = 1 << 6;
    public static final int FLAG_PASSENGER = 1 << 7;

    /** Sortes d'événements — {@code EVENT_*} dans combat/record.rs. */
    public static final int EVENT_ATTACK = 1;
    public static final int EVENT_DAMAGE = 2;
    public static final int EVENT_ENTITY = 3;
    public static final int EVENT_ITEM_USE = 4;

    private static final byte[] NO_BYTES = new byte[0];

    private static MethodHandle context;
    private static MethodHandle world;
    private static MethodHandle history;
    private static MethodHandle recording;
    private static MethodHandle report;
    private static MethodHandle tick;
    private static MethodHandle player;
    private static MethodHandle equipment;
    private static MethodHandle event;
    private static MethodHandle close;

    private static final boolean READY = load();
    private static boolean errorLogged;

    private NativeCombat() {
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

            MethodHandle abi = linker.downcallHandle(lib.find("yf_combat_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                LauncherLog.warn("[COMBAT] Indisponible : content_core.dll annonce la version " + version
                    + " du contrat, " + ABI_VERSION + " attendue — aucun journal de combat ne sera écrit");
                return false;
            }

            // Courts, et ils lisent des tableaux Java : « critical ». Côté natif,
            // chacun recopie ses arguments et rend la main.
            Linker.Option heap = Linker.Option.critical(true);
            context = linker.downcallHandle(lib.find("yf_combat_context").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            world = linker.downcallHandle(lib.find("yf_combat_world").orElseThrow(), FunctionDescriptor.ofVoid());
            // Pas « critical » : cet appel relit des fichiers. Mémoire native, donc.
            history = linker.downcallHandle(lib.find("yf_combat_history").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            recording = linker.downcallHandle(lib.find("yf_combat_recording").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            report = linker.downcallHandle(lib.find("yf_combat_report").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            tick = linker.downcallHandle(lib.find("yf_combat_tick").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            player = linker.downcallHandle(lib.find("yf_combat_player").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            equipment = linker.downcallHandle(lib.find("yf_combat_equipment").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            event = linker.downcallHandle(lib.find("yf_combat_event").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT), heap);
            close = linker.downcallHandle(lib.find("yf_combat_close").orElseThrow(), FunctionDescriptor.ofVoid());
            return true;
        } catch (Throwable error) {
            LauncherLog.err("[COMBAT] Noyau natif indisponible — aucun journal de combat ne sera écrit", error);
            return false;
        }
    }

    /**
     * Entrée dans un monde.
     *
     * @param dir    le dossier de l'instance, où le journal s'écrit
     * @param server l'adresse du serveur, ou {@code null} en solo
     * @param mc     la version du jeu
     */
    public static void context(String dir, String server, String mc) {
        if (!READY || dir == null) {
            return;
        }
        try {
            byte[] d = utf8(dir);
            byte[] s = utf8(server);
            byte[] m = utf8(mc);
            context.invokeExact(MemorySegment.ofArray(d), d.length, MemorySegment.ofArray(s), s.length,
                MemorySegment.ofArray(m), m.length);
        } catch (Throwable error) {
            failed(error);
        }
    }

    /** Changement de monde : le journal en cours se termine. */
    public static void world() {
        if (!READY) {
            return;
        }
        try {
            world.invokeExact();
        } catch (Throwable error) {
            failed(error);
        }
    }

    /**
     * L'historique des duels de l'instance, calculé par le noyau : une ligne
     * d'en-tête (moyenne des notes), puis une ligne par duel, champs séparés
     * par des tabulations — format dans {@code content-core/src/combat/history.rs}.
     *
     * <p>Le noyau relit et renote les journaux : à appeler à l'ouverture d'un
     * écran, jamais à chaque image.
     *
     * @param dir le dossier de l'instance
     * @return le texte, ou {@code null} si le noyau n'est pas disponible
     */
    public static String history(String dir) {
        if (!READY || dir == null || dir.isEmpty()) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            byte[] d = utf8(dir);
            MemorySegment in = arena.allocate(Math.max(1, d.length));
            MemorySegment.copy(d, 0, in, ValueLayout.JAVA_BYTE, 0, d.length);
            MemorySegment out = arena.allocate(HISTORY_BYTES);
            int written = (int) history.invokeExact(in, d.length, out, HISTORY_BYTES);
            if (written <= 0) {
                return written == 0 ? "" : null;
            }
            byte[] bytes = new byte[written];
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, bytes, 0, written);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            failed(error);
            return null;
        }
    }

    /**
     * Le résumé du dernier duel fermé, calculé par le noyau.
     *
     * @param out {@link #REPORT_LEN} nombres, remplis dans l'ordre des
     *            constantes {@code REPORT_*}
     * @return le numéro du résumé : 0 tant qu'aucun duel ne s'est fermé depuis
     *         le lancement, puis 1, 2… — il ne change que pour un nouveau duel
     */
    public static int report(double[] out) {
        if (!READY || out == null || out.length < REPORT_LEN) {
            return 0;
        }
        try {
            return (int) report.invokeExact(MemorySegment.ofArray(out), out.length);
        } catch (Throwable error) {
            failed(error);
            return 0;
        }
    }

    /** Un duel est-il ouvert ? C'est le noyau qui le sait. */
    public static boolean recording() {
        if (!READY) {
            return false;
        }
        try {
            return (int) recording.invokeExact() == 1;
        } catch (Throwable error) {
            failed(error);
            return false;
        }
    }

    /**
     * Un tick du jeu.
     *
     * @param me     {@link #ME_LEN} nombres, dans l'ordre des constantes {@code ME_*}
     * @param others {@code count} joueurs de {@link #OTHER_LEN} nombres, à la suite
     */
    public static void tick(double[] me, double[] others, int count) {
        if (!READY || me == null || me.length < ME_LEN || others == null || others.length < count * OTHER_LEN) {
            return;
        }
        try {
            tick.invokeExact(MemorySegment.ofArray(me), MemorySegment.ofArray(others), count);
        } catch (Throwable error) {
            failed(error);
        }
    }

    /** Qui est derrière un identifiant réseau. */
    public static void player(int id, UUID uuid, String name) {
        if (!READY || uuid == null) {
            return;
        }
        try {
            byte[] n = utf8(name);
            player.invokeExact(id, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), MemorySegment.ofArray(n), n.length);
        } catch (Throwable error) {
            failed(error);
        }
    }

    /** L'équipement d'un joueur : six cases séparées par des points-virgules. */
    public static void equipment(int id, String text) {
        if (!READY) {
            return;
        }
        try {
            byte[] t = utf8(text);
            equipment.invokeExact(id, MemorySegment.ofArray(t), t.length);
        } catch (Throwable error) {
            failed(error);
        }
    }

    /** Un événement de combat — {@code kind} : l'une des constantes {@code EVENT_*}. */
    public static void event(int kind, int a, int b, int c, String info) {
        if (!READY) {
            return;
        }
        try {
            byte[] t = utf8(info);
            event.invokeExact(kind, a, b, c, MemorySegment.ofArray(t), t.length);
        } catch (Throwable error) {
            failed(error);
        }
    }

    /** Le suivi s'arrête : le journal en cours se termine. */
    public static void close() {
        if (!READY) {
            return;
        }
        try {
            close.invokeExact();
        } catch (Throwable error) {
            failed(error);
        }
    }

    private static byte[] utf8(String text) {
        return text == null || text.isEmpty() ? NO_BYTES : text.getBytes(StandardCharsets.UTF_8);
    }

    /** Une fois : à vingt appels par seconde, le journal de l'agent n'y survivrait pas. */
    private static void failed(Throwable error) {
        if (!errorLogged) {
            errorLogged = true;
            LauncherLog.err("[COMBAT] Appel au noyau natif en échec", error);
        }
    }
}
