package com.yuyuframe.launcheragent.runtime.skin;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.NetworkData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Properties;

/**
 * Pose le skin d'un compte hors ligne sur le serveur de jeu, pour que les
 * autres joueurs le voient : envoie {@code /skin url "<adresse>"} à la place
 * du joueur.
 *
 * <p>Notre client ne peut pas changer ce que voient les autres ; seul le
 * serveur le peut. La plupart des serveurs en mode hors ligne ont pour cela le
 * plugin SkinsRestorer, dont {@code /skin url} prend une adresse d'image. On
 * lui donne celle sous laquelle Mojang héberge notre skin
 * ({@link OwnSkin#hostedUrl()}). Le plugin retient le skin : la commande ne
 * part qu'<b>une fois par serveur, par pseudo et par skin</b>.
 *
 * <p>Ce qui est fait pour ne pas écrire dans le chat pour rien :
 * <ul>
 *   <li>rien en solo, rien sans adresse hébergée, rien si le serveur nous
 *       donne déjà ce skin ;</li>
 *   <li>sur les versions qui reçoivent la liste des commandes du serveur,
 *       rien si {@code /skin} n'y est pas (la 1.8.9 ne la reçoit pas : la
 *       commande y part à l'aveugle) ;</li>
 *   <li>un seul nouvel essai, plus tard — pour les serveurs où il faut d'abord
 *       s'identifier ({@code /login}) —, puis plus rien sur ce serveur tant
 *       que le skin n'a pas changé.</li>
 * </ul>
 *
 * <p>Le résultat se lit sur notre propre profil ({@link OwnSkin#serverSkinUrl()}),
 * pas dans le chat : les messages du plugin changent avec sa langue et sa
 * configuration.
 */
public final class SkinPoster {

    private SkinPoster() {
    }

    /** Réglage « Poser mon skin automatiquement sur les serveurs » du module Joueur. */
    public static volatile boolean auto = true;

    /** Ce que l'écran des réglages affiche. */
    public enum Status {
        /** Pas de skin de compte hors ligne à poser. */
        NO_SKIN,
        /** Le skin n'est pas (encore) hébergé : rien à donner au serveur. */
        NOT_HOSTED,
        /** En solo, ou hors d'un monde. */
        NO_SERVER,
        /** Le serveur ne déclare pas {@code /skin}. */
        NO_COMMAND,
        WAITING,
        SENT,
        /** Le serveur nous donne ce skin. */
        POSTED,
        /** La commande est partie, le serveur n'a rien changé. */
        REFUSED,
    }

    private static final long SECOND = 1_000_000_000L;
    /** Le temps que le serveur finisse l'arrivée du joueur, et qu'une connexion automatique passe. */
    private static final long FIRST_AFTER = 5 * SECOND;
    /** Second et dernier essai : le joueur a eu le temps de s'identifier à la main. */
    private static final long RETRY_AFTER = 40 * SECOND;
    /** Délai laissé au serveur pour nous renvoyer notre profil avec le skin. */
    private static final long VERIFY_FOR = 15 * SECOND;
    /** Au-delà, un serveur qui ne déclare toujours pas {@code /skin} ne l'a pas. */
    private static final long COMMAND_WAIT = 60 * SECOND;
    private static final int MAX_ATTEMPTS = 2;

    private static final String COMMAND = "skin";

    private static Object connection;
    private static long joinedAt;
    private static long sentAt;
    private static int attempts;
    private static boolean settled;
    private static String urlBefore;
    private static volatile Status status = Status.NO_SERVER;
    private static boolean sendErrorLogged;

    public static Status status() {
        return status;
    }

    /** Appelé par {@link OwnSkin#tick()}, sur le fil du jeu. */
    static void tick(long now) {
        Object current = NetworkData.connection();
        if (current == null) {
            connection = null;
            status = Status.NO_SERVER;
            return;
        }
        if (current != connection) {
            connection = current;
            joinedAt = now;
            sentAt = 0L;
            attempts = 0;
            settled = false;
        }
        if (settled) {
            return;
        }
        OwnSkin.Skin skin = OwnSkin.current();
        if (skin == null) {
            status = Status.NO_SKIN;
            return;
        }
        String hosted = OwnSkin.hostedUrl();
        String hash = OwnSkin.textureHash(hosted);
        if (hash == null) {
            status = Status.NOT_HOSTED;
            return;
        }
        String key = memoryKey();
        if (key == null) {
            status = Status.NO_SERVER;
            return;
        }
        String wanted = hash + (skin.slim ? ":slim" : ":classic");
        String serverUrl = OwnSkin.serverSkinUrl();
        if (hash.equals(OwnSkin.textureHash(serverUrl))) {
            settle(key, "ok:" + wanted, Status.POSTED);
            return;
        }
        if (sentAt != 0L) {
            awaitResult(now, key, wanted, serverUrl);
            return;
        }
        if (!auto) {
            status = Status.WAITING;
            return;
        }
        String known = remembered(key);
        if (("ok:" + wanted).equals(known)) {
            // Déjà posé ici : le plugin le garde, il arrive avec notre profil.
            settled = true;
            status = Status.POSTED;
            return;
        }
        if (("no:" + wanted).equals(known)) {
            settled = true;
            status = Status.REFUSED;
            return;
        }
        if (now - joinedAt < (attempts == 0 ? FIRST_AFTER : RETRY_AFTER)) {
            status = Status.WAITING;
            return;
        }
        if (NetworkData.hasCommandTree() && !NetworkData.hasCommand(COMMAND)) {
            status = Status.NO_COMMAND;
            // Rien n'est retenu : un serveur qui installe le plugin plus tard sera servi.
            settled = now - joinedAt >= COMMAND_WAIT;
            return;
        }
        send(now, hosted, skin.slim, serverUrl);
    }

    /**
     * Bouton « Poser mon skin sur ce serveur » : envoie la commande tout de
     * suite, quels que soient le réglage et ce qui a déjà été tenté.
     *
     * @return {@code false} s'il n'y a rien à envoyer (pas de serveur, skin non hébergé)
     */
    public static boolean postNow() {
        OwnSkin.Skin skin = OwnSkin.current();
        String hosted = OwnSkin.hostedUrl();
        if (skin == null || OwnSkin.textureHash(hosted) == null || NetworkData.connection() == null || memoryKey() == null) {
            return false;
        }
        attempts = MAX_ATTEMPTS - 1;
        settled = false;
        send(System.nanoTime(), hosted, skin.slim, OwnSkin.serverSkinUrl());
        return true;
    }

    private static void send(long now, String hosted, boolean slim, String serverUrl) {
        // Marqué avant l'envoi : si l'envoi lève, on ne réessaie pas à chaque image.
        attempts++;
        sentAt = now;
        urlBefore = serverUrl;
        status = Status.SENT;
        try {
            // Sans modèle, le plugin le déduit de l'image ; « slim » le force pour les bras fins.
            // La même commande que celle qu'on donne à copier : OwnSkin.command().
            NetworkData.sendCommand(COMMAND + " url \"" + hosted + "\"" + (slim ? " slim" : ""));
            LauncherLog.info("[SKIN] /skin url envoyée au serveur (essai " + attempts + ")");
        } catch (Throwable error) {
            if (!sendErrorLogged) {
                sendErrorLogged = true;
                LauncherLog.err("[SKIN] Envoi de /skin url impossible", error);
            }
        }
    }

    private static void awaitResult(long now, String key, String wanted, String serverUrl) {
        // Le plugin peut reposer l'image sous une autre adresse : un skin qui
        // CHANGE après notre commande vaut réussite, comme un skin identique.
        boolean changed = serverUrl != null && !serverUrl.equals(urlBefore);
        if (changed) {
            LauncherLog.info("[SKIN] Skin posé sur le serveur");
            settle(key, "ok:" + wanted, Status.POSTED);
            return;
        }
        if (now - sentAt < VERIFY_FOR) {
            return;
        }
        if (attempts < MAX_ATTEMPTS && auto) {
            sentAt = 0L;
            status = Status.WAITING;
            return;
        }
        LauncherLog.info("[SKIN] Le serveur n'a pas pris le skin (commande refusée, ou plugin absent)");
        settle(key, "no:" + wanted, Status.REFUSED);
    }

    private static void settle(String key, String outcome, Status result) {
        settled = true;
        status = result;
        remember(key, outcome);
    }

    // ── Ce qui a déjà été fait, serveur par serveur ──────────────────────────

    /**
     * « pseudo@serveur », en minuscules : le plugin retient un skin par joueur,
     * et deux comptes hors ligne n'ont pas le même pseudo. {@code null} en solo.
     */
    private static String memoryKey() {
        Object[] sources = NetworkData.serverAddressSources();
        String name = OwnSkin.selfName();
        if (sources == null || name == null) {
            return null;
        }
        String server = null;
        if (sources[0] instanceof String && !((String) sources[0]).trim().isEmpty()) {
            server = ((String) sources[0]).trim();
        } else if (sources[1] instanceof InetSocketAddress) {
            InetSocketAddress remote = (InetSocketAddress) sources[1];
            server = remote.getHostString() + ":" + remote.getPort();
        }
        return server == null ? null : (name + "@" + server).toLowerCase(Locale.ROOT);
    }

    private static final Properties MEMORY = new Properties();
    private static boolean memoryLoaded;

    private static File memoryFile() {
        String appData = System.getenv("APPDATA");
        return appData == null ? null : new File(appData + "\\YuyuFrame\\agent\\skin-servers.properties");
    }

    private static String remembered(String key) {
        if (!memoryLoaded) {
            memoryLoaded = true;
            File file = memoryFile();
            if (file != null && file.isFile()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    MEMORY.load(in);
                } catch (Exception error) {
                    LauncherLog.err("[SKIN] Mémoire des serveurs illisible : " + file, error);
                }
            }
        }
        return MEMORY.getProperty(key);
    }

    private static void remember(String key, String outcome) {
        if (outcome.equals(remembered(key))) {
            return;
        }
        MEMORY.setProperty(key, outcome);
        File file = memoryFile();
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                LauncherLog.err("[SKIN] Dossier introuvable pour la mémoire des serveurs : " + parent);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                MEMORY.store(out, "Serveurs ou le skin du compte hors ligne a deja ete pose (pseudo@serveur)");
            }
        } catch (Exception error) {
            LauncherLog.err("[SKIN] Mémoire des serveurs non enregistrée : " + file, error);
        }
    }
}
