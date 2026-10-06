package com.yuyuframe.launcheragent.apigraphic.asset;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chargement d'images DEPUIS N'IMPORTE QUELLE URL HTTPS, EN MÉMOIRE
 * UNIQUEMENT — pas d'écriture disque, contrairement à l'ancien mécanisme
 * icône-par-icône de {@code ModrinthContentScreen} qui passait par {@code
 * ContentBridge.downloadFile} + un fichier de cache (voir historique de
 * session : demande explicite de l'utilisateur, "je veux éviter de passer
 * par le téléchargement de l'image, je veux tout faire par internet").
 * Décodage côté Rust ({@code content-core/src/image/fetch.rs::fetch_image_rgba},
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

    // Bornage mémoire — BUG DE CONCEPTION CORRIGÉ (retour utilisateur : "vérifie
    // la mémoire RAM utilisée") : ce cache est PERMANENT pour la session (choix
    // explicite, voir javadoc de classe) mais était jusqu'ici totalement
    // ILLIMITÉ — des captures de galerie pleine résolution (souvent
    // 1920×1080+, ~8 Mo chacune UNE FOIS décodées en ARGB) s'accumulaient pour
    // CHAQUE projet consulté durant la session, sans jamais être libérées.
    // Éviction FIFO simple (pas une vraie LRU — suivre le "dernier accès"
    // coûterait une écriture supplémentaire sur CHAQUE get(), appelé à CHAQUE
    // FRAME par tout widget affichant une image, un coût récurrent jugé pire
    // que l'imprécision d'un FIFO) dès que le total dépasse MAX_CACHE_BYTES :
    // une image évincée puis re-consultée est simplement re-téléchargée
    // (cache-miss ordinaire, pas un bug).
    private static final long MAX_CACHE_BYTES = 150L * 1024 * 1024;
    private static final Queue<String> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicLong cacheBytes = new AtomicLong();

    // Pool de threads BORNÉ — BUG DE PERFORMANCE CORRIGÉ (retour utilisateur :
    // "beaucoup de latence dans l'interface") : avant, CHAQUE image manquante
    // (icône de résultat, bannière/badge inline de description, voisin de
    // galerie préchargé...) déclenchait un `new Thread(...).start()` DÉDIÉ —
    // en rafale (ex. défilement rapide dans une liste de 30+ résultats, ou
    // ouverture d'une page de détail avec une grosse galerie), ça pouvait
    // créer des DIZAINES de threads OS concurrents en une fraction de
    // seconde, chacun bloqué sur une requête réseau — la création de threads
    // natifs n'est PAS gratuite (allocation de pile OS, changements de
    // contexte) et rivalise avec les threads du jeu lui-même pour le CPU,
    // perçu comme des à-coups/latence dans l'interface. Remplacé par un pool
    // fixe de 4 threads démons — borne le nombre de requêtes réseau EN VOL
    // simultanément, les fetches en surplus attendent simplement en file
    // (FETCHING empêche de toute façon les doublons pour une MÊME URL).
    private static final ExecutorService FETCH_POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int n;
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "UiRemoteImage-Fetch-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * Image déjà chargée pour cette URL, ou {@code null} si pas encore prête
     * (fetch automatiquement déclenché en arrière-plan la première fois,
     * silencieusement ignoré si déjà en cours). {@code null}/vide en entrée
     * = toujours {@code null} en sortie, sans déclencher quoi que ce soit
     * (évite une tâche de fetch inutile pour une carte sans icône).
     */
    public static BufferedImage get(String url) {
        if (url == null || url.isEmpty()) return null;
        BufferedImage cached = CACHE.get(url);
        if (cached != null) return cached;
        // Une URL qui a échoué n'est PAS redemandée : get() est appelé à chaque
        // image par tout widget qui attend son image, donc une image
        // introuvable (404, format illisible) relançait une requête réseau en
        // boucle, 60 fois par seconde, tant que l'écran restait ouvert.
        if (FAILED.contains(url)) return null;
        if (FETCHING.add(url)) {
            FETCH_POOL.submit(() -> fetchAndDecode(url));
        }
        return null;
    }

    /** URL dont le chargement a échoué pour cette session — voir {@link #get}. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** Vrai si cette image ne viendra pas (échec réseau ou de décodage) : l'appelant peut cesser de lui garder une place. */
    public static boolean failed(String url) {
        return url != null && FAILED.contains(url);
    }

    // ── Versions réduites ────────────────────────────────────────────────────
    //
    // Une image s'affiche presque toujours plus petite que sa source (vignette
    // de galerie, bannière de description). La dessiner telle quelle envoie
    // la SOURCE au GPU — sur Blaze3D, dans l'atlas partagé de 2048² (voir
    // Blaze3DRect) : trois captures 1920×1080 le remplissent, et tout ce qui
    // suit ne s'affiche plus. scaled() rend une copie ramenée à la taille
    // d'affichage, réduite hors du thread de rendu.
    private static final Map<String, BufferedImage> SCALED = new ConcurrentHashMap<>();
    private static final Set<String> SCALING = ConcurrentHashMap.newKeySet();
    /** Au-delà, le cache des copies réduites est vidé d'un bloc (elles se recalculent depuis la source). */
    private static final int MAX_SCALED_ENTRIES = 96;

    /**
     * Copie de l'image tenant dans {@code maxW × maxH} (proportions gardées,
     * jamais agrandie), ou {@code null} tant qu'elle n'est pas prête. Même
     * contrat que {@link #get} : non bloquant, à rappeler à chaque image.
     * Passer la même clé de texture pour une même taille demandée.
     */
    public static BufferedImage scaled(String url, int maxW, int maxH) {
        if (url == null || url.isEmpty() || maxW <= 0 || maxH <= 0) return null;
        String key = url + "|" + maxW + "x" + maxH;
        BufferedImage ready = SCALED.get(key);
        if (ready != null) return ready;
        BufferedImage source = get(url);
        if (source == null) return null;
        float ratio = Math.min(1f, Math.min(maxW / (float) source.getWidth(), maxH / (float) source.getHeight()));
        if (ratio >= 1f) return source; // déjà assez petite : rien à copier
        if (SCALING.add(key)) {
            FETCH_POOL.submit(() -> {
                try {
                    int w = Math.max(1, Math.round(source.getWidth() * ratio));
                    int h = Math.max(1, Math.round(source.getHeight() * ratio));
                    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = out.createGraphics();
                    try {
                        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                            java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                        // Réduction en UNE passe bilinéaire : au-delà d'un
                        // facteur 2 elle saute des pixels, d'où un passage
                        // intermédiaire à mi-chemin pour les grandes réductions.
                        BufferedImage from = source;
                        if (ratio < 0.5f) {
                            int mw = Math.max(w, source.getWidth() / 2), mh = Math.max(h, source.getHeight() / 2);
                            BufferedImage mid = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
                            java.awt.Graphics2D gm = mid.createGraphics();
                            try {
                                gm.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                gm.drawImage(source, 0, 0, mw, mh, null);
                            } finally {
                                gm.dispose();
                            }
                            from = mid;
                        }
                        g.drawImage(from, 0, 0, w, h, null);
                    } finally {
                        g.dispose();
                    }
                    if (SCALED.size() >= MAX_SCALED_ENTRIES) SCALED.clear();
                    SCALED.put(key, out);
                } catch (Throwable t) {
                    LauncherLog.warn("[UiRemoteImage] scaled(" + url + "): " + t);
                } finally {
                    SCALING.remove(key);
                }
            });
        }
        return null;
    }

    private static void fetchAndDecode(String url) {
        try {
            if (!ContentBridge.ensureLoaded()) return;
            byte[] payload = ContentBridge.fetchImageRgba(url);
            BufferedImage img = decode(payload);
            if (img != null) {
                CACHE.put(url, img);
                INSERTION_ORDER.add(url);
                evictIfNeeded(cacheBytes.addAndGet(4L * img.getWidth() * img.getHeight()));
            } else {
                FAILED.add(url);
            }
        } catch (Throwable t) {
            FAILED.add(url);
            LauncherLog.warn("[UiRemoteImage] fetch(" + url + "): " + t);
        } finally {
            FETCHING.remove(url);
        }
    }

    private static void evictIfNeeded(long totalBytes) {
        while (totalBytes > MAX_CACHE_BYTES) {
            String oldest = INSERTION_ORDER.poll();
            if (oldest == null) break; // rien de plus à évincer (course bénigne avec une insertion concurrente)
            BufferedImage removed = CACHE.remove(oldest);
            if (removed == null) continue; // déjà évincée entre-temps, ignore
            totalBytes = cacheBytes.addAndGet(-4L * removed.getWidth() * removed.getHeight());
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
