package com.yuyuframe.launcheragent.runtime.skin;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La tête des comptes Minecraft <b>hors ligne</b>, pour la liste des comptes
 * de l'écran-titre.
 *
 * <p>Le service d'avatars qui sert la tête d'un compte Microsoft ne connaît pas
 * le skin d'un compte hors ligne : il rend Steve. Ce skin vit dans le launcher,
 * qui en donne le fichier par le canal du jeu (question {@code skin⇥<uuid>}) ;
 * la tête en est découpée ici.
 *
 * <p>Rien ne bloque : {@link #face} rend ce qu'il a, demande le reste, et
 * {@link #tick()} relève les réponses. Une tête est redemandée de loin en
 * loin, le skin pouvant changer dans le launcher pendant que le jeu tourne.
 */
public final class AccountFaces {

    private AccountFaces() {
    }

    /** Une tête prête à dessiner, et la clé sous laquelle le moteur de rendu la garde. */
    public static final class Face {
        public final BufferedImage image;
        /** Change avec le fichier : une nouvelle image ne doit pas être confondue avec l'ancienne. */
        public final String key;

        Face(BufferedImage image, String key) {
            this.image = image;
            this.key = key;
        }
    }

    private static final class Entry {
        /** {@code null} : ce compte n'a pas de skin dans le launcher. */
        volatile Face face;
        volatile String path;
        long askedAt;
        long request;
    }

    private static final long REFRESH_NANOS = 30_000_000_000L;
    /** Côté d'une tête dans un skin, et taille à laquelle elle est agrandie — sans lissage, ce sont des pixels. */
    private static final int HEAD = 8;
    private static final int OUT = 64;

    /** Lue par le fil du rendu, remplie par celui du jeu et par le fil de lecture. */
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOGGED = new HashMap<>();

    /** La tête de ce compte hors ligne, ou {@code null} tant qu'elle n'est pas là (ou s'il n'a pas de skin). */
    public static Face face(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        Entry entry = ENTRIES.computeIfAbsent(uuid, k -> new Entry());
        return entry.face;
    }

    /** À chaque image, sur le fil du jeu : pose les questions dues et relève les réponses. */
    public static void tick() {
        if (ENTRIES.isEmpty() || !NativeLink.available()) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<String, Entry> each : ENTRIES.entrySet()) {
            Entry entry = each.getValue();
            if (entry.request != 0L) {
                String reply = NativeLink.poll(entry.request);
                if (reply != NativeLink.PENDING) {
                    entry.request = 0L;
                    read(each.getKey(), entry, reply);
                }
            } else if (entry.askedAt == 0L || now - entry.askedAt >= REFRESH_NANOS) {
                entry.askedAt = now;
                entry.request = NativeLink.send("skin\t" + each.getKey());
            }
        }
    }

    /** {@code skin⇥<modèle ou ->⇥<adresse ou ->⇥<chemin du PNG ou ->} */
    private static void read(String uuid, Entry entry, String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 4 || !"skin".equals(parts[0])) {
            // Un launcher plus ancien ne connaît pas la question : la pastille à l'initiale reste.
            return;
        }
        String path = "-".equals(parts[3]) ? null : parts[3];
        if (path == null) {
            entry.path = null;
            entry.face = null;
            return;
        }
        if (path.equals(entry.path)) {
            return;
        }
        entry.path = path;
        Thread worker = new Thread(() -> load(uuid, entry, path), "yf-face");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hors du fil du jeu : lecture du disque et découpe. */
    private static void load(String uuid, Entry entry, String path) {
        try {
            BufferedImage skin = ImageIO.read(new File(path));
            if (skin == null || skin.getWidth() < OwnSkin.SIZE || skin.getHeight() < OwnSkin.SIZE / 2) {
                logOnce(path, "[SKIN] " + path + " n'est pas un skin : tête non affichée", null);
                return;
            }
            // Le visage, puis la surcouche (cheveux, chapeau) par-dessus : sans elle les skins sont chauves.
            BufferedImage face = new BufferedImage(OUT, OUT, BufferedImage.TYPE_INT_ARGB);
            int cell = OUT / HEAD;
            for (int y = 0; y < HEAD; y++) {
                for (int x = 0; x < HEAD; x++) {
                    int base = skin.getRGB(HEAD + x, HEAD + y) | 0xFF000000;
                    int over = skin.getRGB(40 + x, HEAD + y);
                    int color = (over >>> 24) >= 128 ? over | 0xFF000000 : base;
                    for (int dy = 0; dy < cell; dy++) {
                        for (int dx = 0; dx < cell; dx++) {
                            face.setRGB(x * cell + dx, y * cell + dy, color);
                        }
                    }
                }
            }
            // Le chemin a pu changer pendant la lecture : la plus récente gagne.
            if (path.equals(entry.path)) {
                entry.face = new Face(face, "yf-face:" + uuid + ":" + path);
            }
        } catch (Throwable error) {
            logOnce(path, "[SKIN] Tête du compte illisible : " + path, error);
        }
    }

    private static synchronized void logOnce(String path, String message, Throwable error) {
        if (LOGGED.put(path, Boolean.TRUE) != null) {
            return;
        }
        if (error == null) {
            LauncherLog.err(message);
        } else {
            LauncherLog.err(message, error);
        }
    }
}
