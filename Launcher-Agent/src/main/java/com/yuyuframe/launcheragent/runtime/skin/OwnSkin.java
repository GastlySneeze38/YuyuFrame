package com.yuyuframe.launcheragent.runtime.skin;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Le skin d'un compte Minecraft <b>hors ligne</b>, tel que le joueur l'a choisi
 * dans le launcher.
 *
 * <p>Un tel compte n'a pas de skin chez Mojang, donc aucun serveur ne lui en
 * donne de lui-même, et le jeu l'affiche en Steve. Deux choses partent d'ici :
 * <ul>
 *   <li>le PNG du launcher, que la liaison de chaque version pose sur notre
 *       propre joueur <b>quand le jeu n'a aucun skin pour lui</b> (solo,
 *       serveur qui n'en transmet pas) — un skin donné par le serveur passe
 *       toujours devant, c'est celui que tout le monde voit ;</li>
 *   <li>l'adresse sous laquelle Mojang héberge ce même PNG, que
 *       {@link SkinPoster} donne au serveur de jeu pour que les autres le
 *       voient aussi.</li>
 * </ul>
 *
 * <p>Le skin est demandé au launcher par le canal du jeu (question
 * {@code skin}), et redemandé de loin en loin : le compte peut changer en
 * cours de partie, et l'adresse hébergée arriver après coup. Pour un compte
 * Microsoft le launcher ne rend rien, et rien ici n'est actif.
 *
 * <p>Aucun type du jeu : la texture elle-même est créée par
 * {@code apimixin/<version>/skin}. Voir docs/LauncherAgent/skins-hors-ligne.md.
 */
public final class OwnSkin {

    private OwnSkin() {
    }

    /** Un skin prêt à poser. Immuable. */
    public static final class Skin {
        /** 64×64, ligne par ligne, en ARGB. */
        public final int[] argb;
        /** Le même, en PNG 64×64 (un skin 64×32 a été remis au format moderne). */
        public final byte[] png;
        /** Bras fins (modèle « Alex ») ou classiques. */
        public final boolean slim;
        /** Change quand l'image ou le modèle change : la liaison refait alors sa texture. */
        public final int revision;

        Skin(int[] argb, byte[] png, boolean slim, int revision) {
            this.argb = argb;
            this.png = png;
            this.slim = slim;
            this.revision = revision;
        }
    }

    public static final int SIZE = 64;

    private static final long ASK_EVERY_NANOS = 15_000_000_000L;
    private static final long PROFILE_EVERY_NANOS = 1_000_000_000L;

    private static volatile Skin current;
    /** L'adresse de ce skin chez Mojang, ou {@code null} tant qu'il n'y est pas hébergé. */
    private static volatile String hostedUrl;
    /** L'UUID de notre joueur dans le monde courant — celui que le serveur lui a donné. */
    private static volatile UUID selfId;
    private static volatile String selfName;
    /** L'adresse du skin que le SERVEUR donne à notre joueur, ou {@code null} s'il n'en donne pas. */
    private static volatile String serverSkinUrl;

    private static volatile Thread gameThread;

    private static long request;
    private static long lastAsk;
    private static long lastProfile;
    // Écrits par le fil de lecture (« yf-skin »), lus par le fil du jeu.
    private static volatile String loadedPath;
    private static volatile boolean loadedSlim;
    private static volatile boolean loading;
    private static int revisions;
    private static boolean profileErrorLogged;
    /** Lu et écrit par le fil du rendu, remis à zéro par celui du jeu. */
    private static volatile String lastSeenTextures;

    /** Le skin à poser sur notre joueur, ou {@code null} (compte Microsoft, aucun skin, pas encore lu). */
    public static Skin current() {
        return current;
    }

    public static String hostedUrl() {
        return hostedUrl;
    }

    /** Le pseudo de notre joueur dans le monde courant, ou {@code null} hors d'un monde. */
    static String selfName() {
        return selfName;
    }

    /** Ce joueur est-il le nôtre ? Faux hors d'un monde. */
    public static boolean isSelf(UUID id) {
        return id != null && id.equals(selfId);
    }

    /**
     * Faut-il poser notre skin sur ce joueur ? Seulement sur nous-même, et
     * seulement si le serveur ne lui a donné aucun skin.
     */
    public static boolean wanted(UUID id, boolean serverHasSkin) {
        return !serverHasSkin && current != null && isSelf(id);
    }

    /**
     * La liaison a vu la propriété {@code textures} que le serveur donne à
     * notre joueur ({@code null} : il n'en donne pas). Appelé à chaque lecture
     * du skin : le décodage n'a lieu que si la valeur a changé.
     *
     * @param value le contenu de la propriété, en base64, tel que le serveur l'envoie
     */
    public static void serverTextures(String value) {
        String seen = lastSeenTextures;
        if (value == seen || (value != null && value.equals(seen))) {
            return;
        }
        lastSeenTextures = value;
        serverSkinUrl = value == null ? null : skinUrlOf(value);
    }

    public static String serverSkinUrl() {
        return serverSkinUrl;
    }

    /**
     * Est-on sur le fil du jeu, celui du rendu ? Une texture ne se crée que
     * là : la liaison ne fait rien si le skin est lu d'ailleurs.
     */
    public static boolean onGameThread() {
        return Thread.currentThread() == gameThread;
    }

    /** Le compte vient de changer : redemander sans attendre. */
    public static void refresh() {
        lastAsk = 0L;
    }

    /** À chaque image, sur le fil du jeu. Ne coûte rien quand rien n'est attendu. */
    public static void tick() {
        gameThread = Thread.currentThread();
        long now = System.nanoTime();
        if (request != 0L) {
            String reply = NativeLink.poll(request);
            if (reply != NativeLink.PENDING) {
                request = 0L;
                read(reply);
            }
        } else if (NativeLink.available() && (lastAsk == 0L || now - lastAsk >= ASK_EVERY_NANOS)) {
            lastAsk = now;
            request = NativeLink.send("skin");
        }
        if (lastProfile == 0L || now - lastProfile >= PROFILE_EVERY_NANOS) {
            lastProfile = now;
            readSelf();
        }
        SkinPoster.tick(now);
    }

    private static void readSelf() {
        try {
            Object profile = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER_PROFILE, null);
            if (profile instanceof Object[] && ((Object[]) profile).length == 2 && ((Object[]) profile)[0] instanceof UUID) {
                Object[] parts = (Object[]) profile;
                selfId = (UUID) parts[0];
                selfName = parts[1] instanceof String ? (String) parts[1] : null;
            } else {
                selfId = null;
                selfName = null;
                // Hors d'un monde, ce que le dernier serveur donnait ne vaut plus rien.
                serverTextures(null);
            }
        } catch (Throwable error) {
            selfId = null;
            selfName = null;
            if (!profileErrorLogged) {
                profileErrorLogged = true;
                LauncherLog.err("[SKIN] Profil du joueur illisible", error);
            }
        }
    }

    /** {@code skin⇥<modèle ou ->⇥<adresse chez Mojang ou ->⇥<chemin du PNG ou ->} */
    private static void read(String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 4 || !"skin".equals(parts[0])) {
            // Un launcher plus ancien ne connaît pas la question : rien à afficher, et ce n'est pas une panne.
            return;
        }
        String hosted = "-".equals(parts[2]) ? null : parts[2];
        hostedUrl = textureHash(hosted) == null ? null : hosted;
        String path = "-".equals(parts[3]) ? null : parts[3];
        boolean slim = "slim".equals(parts[1]);
        if (path == null) {
            loadedPath = null;
            current = null;
            return;
        }
        if (path.equals(loadedPath) && slim == loadedSlim) {
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        Thread worker = new Thread(() -> load(path, slim), "yf-skin");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hors du fil du jeu : lecture du disque et décodage. */
    private static void load(String path, boolean slim) {
        try {
            BufferedImage image = ImageIO.read(new File(path));
            if (image == null || image.getWidth() != SIZE || (image.getHeight() != SIZE && image.getHeight() != SIZE / 2)) {
                LauncherLog.err("[SKIN] " + path + " n'est pas un skin (PNG 64×64 ou 64×32)");
                loadedPath = path;
                loadedSlim = slim;
                current = null;
                return;
            }
            int[] argb = SkinPixels.modern(image);
            BufferedImage modern = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            modern.setRGB(0, 0, SIZE, SIZE, argb, 0, SIZE);
            ByteArrayOutputStream png = new ByteArrayOutputStream(4096);
            ImageIO.write(modern, "png", png);
            loadedPath = path;
            loadedSlim = slim;
            current = new Skin(argb, png.toByteArray(), slim, ++revisions);
            LauncherLog.info("[SKIN] Skin du launcher chargé (" + (slim ? "bras fins" : "classique") + ")");
        } catch (Throwable error) {
            // Retenu comme lu : sans cela la lecture, et son erreur, reviendraient à chaque question au launcher.
            loadedPath = path;
            loadedSlim = slim;
            current = null;
            LauncherLog.err("[SKIN] Skin du launcher illisible : " + path, error);
        } finally {
            loading = false;
        }
    }

    // ── Adresses de textures ─────────────────────────────────────────────────

    private static final String TEXTURE_PREFIX = "textures.minecraft.net/texture/";

    /**
     * L'empreinte d'une adresse de texture de Mojang, en minuscules — ce qui
     * identifie l'image, que l'adresse soit écrite en http ou en https.
     * {@code null} si ce n'en est pas une.
     */
    static String textureHash(String url) {
        if (url == null) {
            return null;
        }
        int at = url.indexOf(TEXTURE_PREFIX);
        if (at < 0 || !(url.startsWith("http://") || url.startsWith("https://")) || url.indexOf("://") + 3 != at) {
            return null;
        }
        String hash = url.substring(at + TEXTURE_PREFIX.length());
        if (hash.isEmpty()) {
            return null;
        }
        for (int i = 0; i < hash.length(); i++) {
            if (Character.digit(hash.charAt(i), 16) < 0) {
                return null;
            }
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * L'adresse du skin dans le contenu d'une propriété {@code textures} :
     * du JSON en base64, {@code {"textures":{"SKIN":{"url":"…"}}}}. On n'y
     * cherche qu'une chaîne, à un endroit connu : pas besoin d'un analyseur.
     */
    static String skinUrlOf(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            int skin = json.indexOf("\"SKIN\"");
            if (skin < 0) {
                return null;
            }
            int key = json.indexOf("\"url\"", skin);
            if (key < 0) {
                return null;
            }
            int open = json.indexOf('"', json.indexOf(':', key) + 1);
            int close = open < 0 ? -1 : json.indexOf('"', open + 1);
            return close < 0 ? null : json.substring(open + 1, close);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
