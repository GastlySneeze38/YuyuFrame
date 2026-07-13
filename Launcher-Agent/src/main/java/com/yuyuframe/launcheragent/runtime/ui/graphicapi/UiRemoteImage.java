package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chargement d'images DEPUIS N'IMPORTE QUELLE URL HTTPS, EN MÉMOIRE
 * UNIQUEMENT — pas d'écriture disque, contrairement à l'ancien mécanisme
 * icône-par-icône de {@code ModrinthContentScreen} qui passait par {@code
 * ContentBridge.downloadFile} + un fichier de cache (voir historique de
 * session : demande explicite de l'utilisateur, "je veux éviter de passer
 * par le téléchargement de l'image, je veux tout faire par internet").
 * Décodage côté Rust ({@code content-core/src/modrinth.rs::fetch_image_rgba},
 * crate {@code image}) pour supporter WebP en plus de PNG/JPEG/GIF/BMP/ICO/
 * TIFF — {@code javax.imageio.ImageIO} (Java) ne décode PAS WebP nativement,
 * et ajouter une lib Java tierce aurait demandé de gérer un jar à la main
 * (le projet n'a pas de Maven/Gradle) pour un besoin qu'un seul appel JNI
 * supplémentaire couvre déjà entièrement.
 *
 * API NON-BLOQUANTE — même motif que {@code UiRenderer.drawIcon}/{@code
 * UiFont} : {@link #get} renvoie l'image DÉJÀ en cache mémoire, ou {@code
 * null} si pas encore chargée (déclenche alors automatiquement un fetch sur
 * un thread daemon séparé, jamais le thread de rendu) — sûr à appeler depuis
 * {@code draw()} à CHAQUE frame, exactement comme un simple accès à une Map.
 *
 * Cache PARTAGÉ, permanent pour la durée de la session (pas de cache disque
 * — choix utilisateur explicite : chaque relance du jeu retélécharge, jugé
 * acceptable pour des images de taille "icône").
 */
public final class UiRemoteImage {

    private UiRemoteImage() {}

    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FETCHING = ConcurrentHashMap.newKeySet();

    /**
     * Image déjà chargée pour cette URL, ou {@code null} si pas encore prête
     * (fetch automatiquement déclenché en arrière-plan la première fois,
     * silencieusement ignoré si déjà en cours). {@code null}/vide en entrée
     * = toujours {@code null} en sortie, sans déclencher quoi que ce soit
     * (évite un thread daemon inutile pour une carte sans icône).
     */
    public static BufferedImage get(String url) {
        if (url == null || url.isEmpty()) return null;
        BufferedImage cached = CACHE.get(url);
        if (cached != null) return cached;
        if (FETCHING.add(url)) {
            Thread t = new Thread(() -> fetchAndDecode(url), "UiRemoteImage-Fetch");
            t.setDaemon(true);
            t.start();
        }
        return null;
    }

    private static void fetchAndDecode(String url) {
        try {
            if (!ContentBridge.ensureLoaded()) return;
            byte[] payload = ContentBridge.fetchImageRgba(url);
            BufferedImage img = decode(payload);
            if (img != null) CACHE.put(url, img);
        } catch (Throwable t) {
            LauncherLog.warn("[UiRemoteImage] fetch(" + url + "): " + t);
        } finally {
            FETCHING.remove(url);
        }
    }

    /**
     * Parse le format renvoyé par {@code fetchImageRgba} (Rust) : 8 octets
     * d'en-tête (largeur/hauteur, int32 BIG-ENDIAN chacun) puis
     * largeur*hauteur*4 octets RGBA8 (rouge/vert/bleu/alpha, un octet
     * chacun, ligne par ligne depuis le haut) — voir {@code content.rs} pour
     * le côté qui écrit ce format. {@code BufferedImage} attend de l'ARGB
     * empaqueté en {@code int} (0xAARRGGBB), d'où la reconstruction pixel
     * par pixel ci-dessous. {@code null} si le tableau est vide (échec côté
     * Rust — réseau/décodage/URL non-HTTPS, voir sa javadoc) ou incohérent
     * (taille ne correspondant pas à largeur×hauteur annoncées — défensif,
     * ne devrait jamais arriver si le format Rust ci-dessus reste stable).
     */
    private static BufferedImage decode(byte[] payload) {
        if (payload == null || payload.length < 8) return null;
        int w = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        int h = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        long expected = 8L + (long) w * h * 4L;
        if (w <= 0 || h <= 0 || payload.length != expected) return null;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        int p = 8;
        for (int i = 0; i < argb.length; i++) {
            int r = payload[p] & 0xFF, g = payload[p + 1] & 0xFF, b = payload[p + 2] & 0xFF, a = payload[p + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            p += 4;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }
}
