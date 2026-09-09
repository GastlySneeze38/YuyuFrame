package com.yuyuframe.launcheragent.runtime.mumble;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pont Mumble Link (protocole officiel v2, mémoire partagée Win32).
 *
 * <p>Structure {@code LinkedMem} relue le 2026-09-01 dans la source officielle
 * ({@code plugins/link/LinkedMem.h} du dépôt mumble-voip/mumble) — les offsets
 * ci-dessous en découlent directement, total 5460 octets :
 * <pre>
 *    0  uiVersion          UINT32
 *    4  uiTick             DWORD
 *    8  fAvatarPosition[3] float
 *   20  fAvatarFront[3]    float
 *   32  fAvatarTop[3]      float
 *   44  name[256]          wchar_t
 *  556  fCameraPosition[3] float
 *  568  fCameraFront[3]    float
 *  580  fCameraTop[3]      float
 *  592  identity[256]      wchar_t
 * 1104  context_len        UINT32
 * 1108  context[256]       unsigned char
 * 1364  description[2048]  wchar_t
 * </pre>
 *
 * <h2>Ce que fait Mumble de ces données</h2>
 *
 * D'après {@code plugins/link/link.cpp} et {@code src/murmur/AudioReceiverBuffer.cpp} :
 * <ul>
 *   <li>le plugin n'est activé que si {@code uiVersion} vaut 1 ou 2 ET que
 *       {@code uiTick} a CHANGÉ depuis sa dernière lecture ; il se désactive
 *       si le tick cesse de bouger pendant 5 secondes. D'où l'incrément à
 *       chaque appel de {@link #update} ;</li>
 *   <li>le serveur ne transmet la position d'un joueur à un autre que si leurs
 *       {@code context} sont ÉGAUX octet pour octet
 *       ({@code positionalDataAvailable && sender.ssContext == receiver.ssContext}).
 *       C'est le seul mécanisme de cloisonnement : sans lui, des joueurs de
 *       serveurs ou de dimensions différents s'entendraient positionnellement.
 *       Ce champ n'était pas écrit du tout jusqu'ici — voir {@link #update}.</li>
 * </ul>
 *
 * <p>Aucun code natif à nous : JNA appelle kernel32 (jna.jar/jna-platform.jar,
 * déployés par build.bat et ajoutés au classpath par le launcher — voir
 * {@code agents.rs}), même principe que {@code BorderlessWindowNative} pour
 * User32. Les liaisons viennent de {@code com.sun.jna.platform.win32.Kernel32}
 * plutôt que d'une interface maison : types {@code HANDLE} corrects et surtout
 * {@code GetLastError()} disponible, ce qui manquait cruellement (voir
 * {@link #fail}).
 */
public final class MumbleLinkBridge {

    private MumbleLinkBridge() {}

    /** Taille de {@code LinkedMem} — voir le tableau d'offsets en javadoc de classe. */
    private static final int SIZE = 5460;
    private static final int PAGE_READWRITE = 0x04;
    private static final int FILE_MAP_WRITE = 0x0002;

    // Offsets nommés plutôt que des littéraux disséminés : une erreur d'un
    // octet ici ne produit aucun message, juste un audio positionnel muet ou
    // aberrant.
    private static final int OFF_VERSION      = 0;
    private static final int OFF_TICK         = 4;
    private static final int OFF_AVATAR_POS   = 8;
    private static final int OFF_AVATAR_FRONT = 20;
    private static final int OFF_AVATAR_TOP   = 32;
    private static final int OFF_NAME         = 44;
    private static final int OFF_CAMERA_POS   = 556;
    private static final int OFF_CAMERA_FRONT = 568;
    private static final int OFF_CAMERA_TOP   = 580;
    private static final int OFF_IDENTITY     = 592;
    private static final int OFF_CONTEXT_LEN  = 1104;
    private static final int OFF_CONTEXT      = 1108;
    private static final int OFF_DESCRIPTION  = 1364;
    private static final int MAX_CONTEXT      = 256;

    private static WinNT.HANDLE hMap;
    private static Pointer view;
    private static ByteBuffer buf;
    private static int tick = 0;
    private static boolean ready = false;
    private static boolean failed = false;
    /** Dernier contexte écrit — évite de réécrire 256 octets à chaque frame pour rien. */
    private static String lastContext;

    public static boolean isReady() { return ready; }

    /** Tente l'initialisation si pas déjà fait/échoué — sûr à appeler à chaque frame (voir MumbleLinkModule.onTick). */
    public static synchronized boolean ensureInit() {
        if (ready) return true;
        if (failed) return false;
        return tryInit();
    }

    /**
     * Journalise l'échec AVEC le code d'erreur Windows et arme le drapeau
     * définitif.
     *
     * <p>BUG TROUVÉ (retour utilisateur 2026-09-01, « le mumble link ne
     * fonctionne pas ») : les deux chemins d'échec les plus probables —
     * {@code CreateFileMapping} et {@code MapViewOfFile} qui rendent
     * {@code NULL} — faisaient {@code failed = true; return false;} SANS LE
     * MOINDRE LOG. Le module se taisait alors pour toute la session, et rien,
     * ni en jeu ni dans le fichier de log, ne permettait de distinguer « la
     * mémoire partagée n'a pas pu être créée » de « Mumble n'écoute pas ».
     * C'est exactement l'anti-motif du catch silencieux, appliqué à un retour
     * d'API plutôt qu'à une exception.
     */
    private static boolean fail(String step) {
        failed = true;
        LauncherLog.warn("[MumbleLink] " + step + " a échoué (GetLastError="
            + Kernel32.INSTANCE.GetLastError() + ") — audio positionnel indisponible");
        return false;
    }

    private static boolean tryInit() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            failed = true;
            LauncherLog.warn("[MumbleLink] OS non-Windows — indisponible (mémoire partagée Win32 uniquement)");
            return false;
        }
        try {
            // Mumble fait exactement la même chose de son côté (OpenFileMapping
            // puis CreateFileMapping en repli, voir plugins/link/SharedMemory.cpp) :
            // le premier des deux processus à démarrer crée l'objet, l'autre
            // l'ouvre. L'ordre de lancement jeu/Mumble n'a donc pas d'importance.
            hMap = Kernel32.INSTANCE.CreateFileMapping(
                WinBase.INVALID_HANDLE_VALUE, null, PAGE_READWRITE, 0, SIZE, "MumbleLink");
            if (hMap == null || hMap.getPointer() == Pointer.NULL) return fail("CreateFileMapping");

            view = Kernel32.INSTANCE.MapViewOfFile(hMap, FILE_MAP_WRITE, 0, 0, 0);
            if (view == null || Pointer.nativeValue(view) == 0) {
                boolean r = fail("MapViewOfFile");
                Kernel32.INSTANCE.CloseHandle(hMap);
                hMap = null;
                return r;
            }

            buf = view.getByteBuffer(0, SIZE);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // Champs statiques (name/description), écrits une seule fois — c'est
            // `name` qui donne au plugin son libellé « Link (Minecraft) » dans
            // Mumble (voir mumble_initPositionalData).
            writeUtf16Le("Minecraft", OFF_NAME, 256);
            writeUtf16Le("YuyuFrame LauncherAgent", OFF_DESCRIPTION, 2048);
            // Posé dès l'init, pas seulement au premier update : c'est la
            // toute première chose que Mumble teste.
            buf.putInt(OFF_VERSION, 2);

            Runtime.getRuntime().addShutdownHook(new Thread(MumbleLinkBridge::shutdown, "mumblelink-shutdown"));
            ready = true;
            LauncherLog.info("[MumbleLink] mémoire partagée initialisée — activez le greffon « Link » "
                + "dans Mumble (Configurer > Paramètres > Greffons) pour que l'audio positionnel s'applique");
            return true;
        } catch (Throwable t) {
            failed = true;
            LauncherLog.warn("[MumbleLink] initialisation échouée : " + t);
            return false;
        }
    }

    /**
     * Écrit position/orientation/identité/contexte — appelé CHAQUE frame par
     * {@code MumbleLinkModule.onTick()} tant que le module est actif.
     *
     * <h2>Repère : celui de Badlion, mesuré — {@code (mc.x, mc.z, mc.y)}</h2>
     *
     * Le repère n'est PAS un choix libre. Mumble reconstruit la direction d'un
     * locuteur en soustrayant sa position de la nôtre : ces deux positions ont
     * été écrites par deux clients DIFFÉRENTS, et s'ils ne rangent pas leurs
     * axes dans le même ordre, la géométrie relative est fausse. Il faut donc
     * adopter le repère des clients réellement utilisés, pas celui que la
     * documentation suggère.
     *
     * <p>Relevé le 2026-09-01 dans la mémoire partagée pendant que Badlion
     * Client tournait — client dont l'audio positionnel fonctionne :
     * <pre>
     *   avatar pos   : (5.589344, 996.389404, 120.919998)
     *   avatar front : (0.014117, -0.920187, -0.389758)
     *   avatar top   : (0.006000, -0.391115,  0.920318)
     * </pre>
     * En remettant ces vecteurs dans l'ordre {@code (x, z, y)}, on retrouve
     * EXACTEMENT les formules vanilla {@code calculateViewVector(pitch, yaw)}
     * et {@code calculateViewVector(pitch - 90, yaw)} pour yaw ≈ -179,12° et
     * pitch ≈ 22,94°, à 0,001 près (arrondi flottant / interpolation de frame).
     * Badlion range donc {@code mc.z} dans le slot Y de Mumble et {@code mc.y}
     * dans le slot Z, pour les positions comme pour les vecteurs.
     *
     * <p>Deux conséquences reprises ici :
     * <ul>
     *   <li>le mapping {@code (mc.x, mc.z, mc.y)} remplace la négation de Z
     *       posée plus tôt dans la journée (d'après le wiki Mumble et
     *       fabric-mumblelink-mod) — défendable dans l'absolu, mais
     *       incompatible avec les clients que les joueurs utilisent ;</li>
     *   <li>le vecteur « haut » est le VRAI vecteur haut du joueur, qui dépend
     *       du tangage, et non un {@code (0,1,0)} constant.</li>
     * </ul>
     *
     * <p>Les unités, elles, tombent juste sans conversion : Mumble raisonne en
     * mètres, un bloc Minecraft vaut un mètre.
     *
     * @param context cloisonnement — voir la javadoc de classe. Deux joueurs
     *     ne s'entendent positionnellement que si cette chaîne est identique
     *     chez les deux.
     */
    public static void update(float eyeX, float eyeY, float eyeZ, float yawRad, float pitchRad,
                              String username, String context) {
        if (!ready) return;
        try {
            double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);
            double sinPitch = Math.sin(pitchRad), cosPitch = Math.cos(pitchRad);

            // Vecteur de visée dans le repère Minecraft — Entity.calculateViewVector(pitch, yaw).
            float fX = -(float) (sinYaw * cosPitch);
            float fY = -(float) sinPitch;
            float fZ = (float) (cosYaw * cosPitch);

            // Vecteur « haut » du joueur — Entity.getUpVector(), c'est-à-dire
            // calculateViewVector(pitch - 90, yaw). Dépend du tangage : c'est
            // ce que Badlion transmet, et non un (0,1,0) constant.
            float uX = -(float) (sinYaw * sinPitch);
            float uY = (float) cosPitch;
            float uZ = (float) (cosYaw * sinPitch);

            buf.putInt(OFF_VERSION, 2);
            buf.putInt(OFF_TICK, ++tick);  // doit changer, sinon Mumble coupe au bout de 5 s

            // BUG TROUVÉ (2026-09-01, sonde en jeu : tout était correct SAUF
            // `name` et `description`, vides) — MUMBLE REMET LA STRUCTURE
            // ENTIÈRE À ZÉRO, et il faut donc réécrire les champs « statiques »
            // en permanence. Deux endroits le font :
            //   - mumble_shutdownPositionalData() → sharedMem.reset(), à CHAQUE
            //     perte de lien (le tick fige plus de 5 s : menu principal,
            //     chargement de monde, fenêtre en arrière-plan) ;
            //   - SharedMemory::init(), `if (created) reset();`, quand c'est
            //     Mumble qui crée l'objet en premier.
            // `reset()` écrit un LinkedMem par défaut, soit 5460 octets de
            // zéros. Les champs réécrits chaque frame (version, tick, position,
            // identité, contexte) repartaient donc aussitôt, mais name et
            // description, posés UNE SEULE FOIS à l'init, restaient vides pour
            // le reste de la session. Signature exacte de ce qu'a montré la
            // sonde. Conséquences : Mumble n'affiche plus de jeu lié, et son
            // préfixe de contexte (getPositionalDataContextPrefix, qui EST
            // applicationName, donc name) devient vide — deux joueurs dont l'un
            // a gardé le nom et l'autre non se retrouvent alors dans des
            // contextes différents, donc sans audio positionnel entre eux.
            // Le mod de référence fabric-mumblelink-mod les réécrit lui aussi à
            // chaque tick, pour cette raison exacte.
            writeUtf16Le("Minecraft", OFF_NAME, 256);
            writeUtf16Le("YuyuFrame LauncherAgent", OFF_DESCRIPTION, 2048);

            // Ordre des axes : (mc.x, mc.z, mc.y) — celui de Badlion, voir javadoc.
            putVec(OFF_AVATAR_POS,   eyeX, eyeZ, eyeY);
            putVec(OFF_AVATAR_FRONT, fX,   fZ,   fY);
            putVec(OFF_AVATAR_TOP,   uX,   uZ,   uY);

            // Caméra = avatar en vue première personne.
            putVec(OFF_CAMERA_POS,   eyeX, eyeZ, eyeY);
            putVec(OFF_CAMERA_FRONT, fX,   fZ,   fY);
            putVec(OFF_CAMERA_TOP,   uX,   uZ,   uY);

            writeUtf16Le(username != null ? username : "", OFF_IDENTITY, 256);
            writeContext(context);
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLink] update: " + t);
        }
    }

    private static void putVec(int offset, float x, float y, float z) {
        buf.putFloat(offset, x);
        buf.putFloat(offset + 4, y);
        buf.putFloat(offset + 8, z);
    }

    /**
     * {@code context} est un tableau d'octets AVEC longueur explicite
     * ({@code context_len}), pas une chaîne terminée par un zéro : Mumble le
     * lit par {@code assign(ptr, context_len)}. On écrit donc la longueur, et
     * on ne réécrit que sur changement (une adresse de serveur ne bouge pas
     * d'une frame à l'autre).
     */
    private static void writeContext(String context) {
        String value = context != null ? context : "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(bytes.length, MAX_CONTEXT);
        // Le cache seul ne suffit PAS : Mumble remet toute la structure à zéro
        // à chaque perte de lien (voir update()), ce qui viderait le contexte
        // sans que notre cache ne s'en aperçoive — il resterait vide jusqu'au
        // prochain changement de serveur ou de dimension, c'est-à-dire
        // potentiellement jamais. On confronte donc le cache à ce qui est
        // RÉELLEMENT en mémoire (FILE_MAP_WRITE donne une vue lecture/écriture).
        if (value.equals(lastContext) && buf.getInt(OFF_CONTEXT_LEN) == len) return;
        lastContext = value;

        for (int i = 0; i < len; i++) buf.put(OFF_CONTEXT + i, bytes[i]);
        // Le reste est remis à zéro : sans ça, passer d'un contexte long à un
        // contexte court laisserait la queue de l'ancien en mémoire — invisible
        // tant que context_len est juste, mais piégeux à la lecture d'un dump.
        for (int i = len; i < MAX_CONTEXT; i++) buf.put(OFF_CONTEXT + i, (byte) 0);
        buf.putInt(OFF_CONTEXT_LEN, len);
    }

    /** Écrit s en UTF-16LE à l'offset donné, avec null terminator. maxChars = nombre de wchar_t alloués (null terminator compris). */
    private static void writeUtf16Le(String s, int offset, int maxChars) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
        int len = Math.min(bytes.length, (maxChars - 1) * 2);
        for (int i = 0; i < len; i++) buf.put(offset + i, bytes[i]);
        buf.put(offset + len,     (byte) 0);
        buf.put(offset + len + 1, (byte) 0);
    }

    private static void shutdown() {
        try {
            if (view != null) Kernel32.INSTANCE.UnmapViewOfFile(view);
            if (hMap != null) Kernel32.INSTANCE.CloseHandle(hMap);
        } catch (Throwable ignored) {}
    }
}
