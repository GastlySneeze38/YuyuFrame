package com.yuyuframe.launcheragent.apigraphic.effect;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * La clarté du fond, là où un texte est posé à nu sur le monde (2026-11-21) :
 * sur un ciel clair, un titre blanc disparaissait. Le texte posé sur le décor
 * passe donc au noir sur un fond clair et reste blanc sur un fond sombre
 * ({@link #adapt}).
 *
 * <p><b>D'où vient la clarté.</b> La chaîne de flou du verre réduit déjà le
 * monde à une petite image (le niveau le plus profond, 1/16 de l'écran) ; une
 * fois toutes les quelques images, elle est relue par le CPU
 * ({@code Blaze3DGpu.readTexture}, {@code Gl3Blur}) et réduite ici à une
 * grille d'au plus {@value #GRID_MAX} cases de large. Sur Blaze3D la lecture
 * est asynchrone : la grille a une ou deux images de retard, invisible sur un
 * fond flou.
 *
 * <p><b>Ce qui est posé après la capture</b> (le voile de {@code UiAurora},
 * la bande sombre, les losanges) n'est pas dans l'image relue : chaque décor
 * déclare sa couverture sombre à l'image courante ({@link #cover}), et la
 * clarté rendue en tient compte. Les couvertures sont oubliées à chaque
 * image ({@link #beginFrame}).
 *
 * <p>Sans grille (pas de verre, rien encore relu, relevé trop vieux), la
 * clarté est inconnue ({@code -1}) et le texte garde sa couleur : c'est le
 * fond opaque sombre d'avant.
 */
public final class UiBackdropTone {
    private UiBackdropTone() {}

    /** Largeur maximale de la grille de clarté. */
    public static final int GRID_MAX = 64;
    /** Un relevé plus vieux ne vaut plus rien (fond changé, écran rouvert). */
    private static final long STALE_NANOS = 1_500_000_000L;
    /** Une lecture toutes les N chaînes de flou plein écran. */
    public static final int READ_INTERVAL = 6;

    private static float[] grid;
    private static int gridW, gridH;
    private static long updatedNanos;

    /** Une couverture sombre posée après la capture : son opacité en un point (u, v de 0 à 1, v vers le haut). */
    public interface Cover {
        float alphaAt(float u, float v);
    }

    private static final List<Cover> covers = new ArrayList<>();
    private static int frameCounter;

    /**
     * Début d'une image : les décors redéclarent leurs couvertures, et la page
     * tranche sa teinte d'après la clarté de TOUT le fond (voir
     * {@link #pageLight}).
     */
    public static void beginFrame() {
        covers.clear();
        float tone = screenTone();
        boolean was = pageIsLight;
        if (tone >= 0f) {
            // Un écart pour basculer : un fond à la limite ne fait pas clignoter la page.
            if (!pageIsLight && tone > LIGHT_ON) pageIsLight = true;
            else if (pageIsLight && tone < LIGHT_OFF) pageIsLight = false;
        } else {
            // Rien de mesuré (pas de verre, relevé trop vieux) : le blanc d'avant.
            pageIsLight = false;
        }
        // Journalisé à chaque bascule et au premier relevé : de quoi régler les seuils sur un vrai monde.
        if (was != pageIsLight || (tone >= 0f && !toneLogged)) {
            toneLogged = tone >= 0f;
            LauncherLog.ui(1, "[UiBackdropTone] clarté du fond " + (tone < 0f ? "inconnue" : String.format(java.util.Locale.ROOT, "%.3f", tone))
                + " -> texte " + (pageIsLight ? "encre" : "blanc"));
        }
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1f : Math.min(1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        float target = pageIsLight ? 1f : 0f;
        // Le passage d'une teinte à l'autre en un fondu court plutôt qu'un saut.
        pageLight += (target - pageLight) * (1f - (float) Math.exp(-dt * 10f));
        if (Math.abs(target - pageLight) < 0.002f) pageLight = target;
    }

    /**
     * La teinte de la page, de 0 (blanc) à 1 (encre). UNE décision pour toute
     * la page (2026-11-21) : un titre noir au-dessus d'un compte blanc ne se
     * lit pas comme une page. Elle vient de la clarté moyenne de tout le fond
     * sous le voile ({@link #screenTone}), sans les décors propres à une page
     * (bande, losanges) : sur le même monde, toutes les pages prennent la
     * même teinte. (D'abord un vote des textes : l'accueil, dont les seuls
     * textes posés sur le décor sont bas, sur le sol plus sombre, restait
     * blanc quand les autres pages passaient au noir.)
     */
    private static float pageLight;
    private static boolean pageIsLight, toneLogged;
    private static long lastDebugNanos;
    private static long lastFrameNanos;

    /** Le voile commun à tous les écrans ({@code UiAurora}) : il compte dans la teinte de la page. */
    private static volatile Cover veil;

    /** Le voile posé sur tout le fond, le même à chaque image. */
    public static void veil(Cover cover) {
        veil = cover;
    }

    /**
     * La clarté moyenne de tout le fond, voile compris, en lumière LINÉAIRE
     * (comme le contraste du WCAG : un bleu ciel pastel paraît clair même si
     * sa valeur brute est moyenne) ; {@code -1} sans relevé.
     */
    private static float screenTone() {
        float[] g = grid;
        if (g == null || System.nanoTime() - updatedNanos > STALE_NANOS) return -1f;
        int w = gridW, h = gridH;
        Cover v = veil;
        float sum = 0f;
        int n = 0;
        // Les 60 % du haut seulement (la rangée 0 est en bas) : c'est là que
        // sont les titres et les commandes ; le bas, sous le voile le plus
        // dense, rendait « moyen » un fond franchement clair (2026-11-21).
        int from = (int) Math.floor(h * (1f - TONE_SHARE));
        float rawSum = 0f, dimSum = 0f, allSum = 0f;
        for (int y = 0; y < h; y++) {
            float dim = v == null ? 0f : Math.max(0f, Math.min(1f, v.alphaAt(0.5f, (y + 0.5f) / h)));
            for (int x = 0; x < w; x++) {
                float raw = g[y * w + x];
                allSum += raw;
                if (y < from) continue;
                sum += (float) Math.pow(raw * (1f - dim), 2.2);
                rawSum += raw;
                dimSum += dim;
                n++;
            }
        }
        // La décision porte sur la clarté BRUTE relue (2026-11-21, mesuré en
        // jeu) : sur un gris très clair, elle valait 0,618, et lui retirer le
        // voile puis la passer en lumière linéaire la ramenait à 0,19 — sous
        // le seuil, la page restait blanche. La correction comptait deux fois
        // l'assombrissement ; la valeur brute sépare bien les fonds (ciel ou
        // gris clair vers 0,6, nuit ou grotte entre 0,1 et 0,3).
        float tone = n > 0 ? rawSum / n : -1f;
        // DÉBOGAGE (2026-11-21) : ce que voit la mesure, toutes les 3 s, pour
        // régler la détection sur un vrai monde.
        long now = System.nanoTime();
        if (n > 0 && now - lastDebugNanos > 3_000_000_000L) {
            lastDebugNanos = now;
            LauncherLog.ui(1, String.format(java.util.Locale.ROOT,
                "[UiBackdropTone] débogage : grille %dx%d (lue il y a %d ms), clarté brute haut %.3f / écran %.3f, voile moyen %.2f, "
                    + "clarté linéaire sous le voile %.3f (décision sur la brute, seuils %.2f / %.2f) -> %s",
                w, h, (now - updatedNanos) / 1_000_000L, rawSum / n, allSum / (w * h), dimSum / n, sum / n,
                LIGHT_ON, LIGHT_OFF, pageIsLight ? "encre" : "blanc"));
        }
        return tone;
    }
    /**
     * Seuils de bascule vers l'encre et de retour au blanc, sur la clarté
     * brute relue (0 à 1) ; un écart entre les deux pour ne pas clignoter.
     */
    private static final float LIGHT_ON = 0.50f, LIGHT_OFF = 0.42f;
    /** Part de la hauteur de l'écran, depuis le haut, qui décide la teinte de la page. */
    private static final float TONE_SHARE = 0.6f;

    /** Un décor sombre posé par-dessus le monde à cette image. */
    public static void cover(Cover cover) {
        if (cover != null) covers.add(cover);
    }

    /** Vrai une fois toutes les {@link #READ_INTERVAL} demandes : la chaîne de flou relit alors son niveau le plus profond. */
    public static boolean readDue() {
        return (++frameCounter % READ_INTERVAL) == 0;
    }

    /**
     * Une image relue : RGBA8, rangées serrées, la rangée 0 en BAS. Réduite à
     * la grille par moyenne de cases. Lue tout de suite : le tampon est fermé
     * après l'appel.
     */
    public static void accept(ByteBuffer rgba, int width, int height) {
        try {
            if (rgba == null || width <= 0 || height <= 0 || rgba.capacity() < width * height * 4) return;
            int gw = Math.min(GRID_MAX, width);
            int gh = Math.max(1, Math.round(height * gw / (float) width));
            float[] sum = new float[gw * gh];
            int[] count = new int[gw * gh];
            for (int y = 0; y < height; y++) {
                int gy = Math.min(gh - 1, y * gh / height);
                int row = y * width * 4;
                for (int x = 0; x < width; x++) {
                    int i = row + x * 4;
                    float r = (rgba.get(i) & 0xFF) / 255f;
                    float g = (rgba.get(i + 1) & 0xFF) / 255f;
                    float b = (rgba.get(i + 2) & 0xFF) / 255f;
                    int cell = gy * gw + Math.min(gw - 1, x * gw / width);
                    sum[cell] += 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    count[cell]++;
                }
            }
            for (int i = 0; i < sum.length; i++) sum[i] = count[i] > 0 ? sum[i] / count[i] : 0f;
            grid = sum;
            gridW = gw;
            gridH = gh;
            updatedNanos = System.nanoTime();
        } catch (Throwable t) {
            LauncherLog.err("[UiBackdropTone] lecture du fond illisible", t);
        }
    }

    /**
     * La clarté (0 noir, 1 blanc) du fond sous un rectangle de l'écran
     * ({@code x1..x2}, {@code y1..y2}, y vers le haut, dans le repère
     * {@code vw} x {@code vh}), décors sombres compris ; {@code -1} si elle
     * n'est pas connue.
     */
    public static float luminance(float x1, float y1, float x2, float y2, int vw, int vh) {
        float[] g = grid;
        if (g == null || vw <= 0 || vh <= 0 || System.nanoTime() - updatedNanos > STALE_NANOS) return -1f;
        int w = gridW, h = gridH;
        int cx1 = clamp((int) Math.floor(Math.min(x1, x2) / vw * w), w);
        int cx2 = clamp((int) Math.floor(Math.max(x1, x2) / vw * w), w);
        int cy1 = clamp((int) Math.floor(Math.min(y1, y2) / vh * h), h);
        int cy2 = clamp((int) Math.floor(Math.max(y1, y2) / vh * h), h);
        float sum = 0f;
        int n = 0;
        for (int y = cy1; y <= cy2; y++) {
            for (int x = cx1; x <= cx2; x++) {
                sum += g[y * w + x];
                n++;
            }
        }
        float lum = n > 0 ? sum / n : 0f;
        // Les décors posés après la capture assombrissent d'autant.
        float u = (x1 + x2) / 2f / vw, v = (y1 + y2) / 2f / vh;
        for (Cover c : covers) {
            float a = Math.max(0f, Math.min(1f, c.alphaAt(u, v)));
            lum *= 1f - a;
        }
        Cover dim = veil;
        if (dim != null) lum *= 1f - Math.max(0f, Math.min(1f, dim.alphaAt(u, v)));
        return lum;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /** L'encre du texte sur fond clair : un noir légèrement violet, comme l'encre du site. */
    public static final UiColor INK = new UiColor(18, 16, 26, 255);

    /** Le blanc du texte sur fond sombre : un blanc franc, pas le gris clair de l'interface. */
    public static final UiColor PAPER = new UiColor(248, 247, 252, 255);

    /**
     * La teinte de la page : 0 blanc, 1 encre (voir {@link #pageLight}). Le
     * paramètre est la clarté sous l'appelant, gardée pour la lisibilité des
     * appels : la décision est celle de la page, pas du point.
     */
    public static float lightness(float luminance) {
        return pageLight;
    }

    /**
     * La couleur d'un texte posé sur le décor : DEUX teintes seulement, le
     * blanc ou l'encre, celle de la page — plus de gris ni de couleur
     * (2026-11-21 : « si c'est noir toute la page est en noir »). Seule
     * l'opacité d'origine reste (le fondu au bord d'une liste).
     */
    public static UiColor adapt(UiColor color, float luminance) {
        if (color == null) return null;
        return UiColor.lerp(PAPER, INK, pageLight).withAlpha(color.a);
    }

    /**
     * Le fond d'un contrôle presque transparent posé sur le décor (segments
     * de filtre, carte d'ami hors ligne, « Ajouter un favori ») : sur une page
     * claire, c'est LUI qui s'assombrit, et son texte reste blanc
     * (2026-11-21) — un mot noir sur un voile clair ne se distinguait plus
     * du fond.
     */
    public static UiColor controlFill(UiColor onDark) {
        return UiColor.lerp(onDark, INK.withAlpha(0.62f), pageLight);
    }

    /** L'ombre d'un texte sur ce fond : sombre sous le blanc, aucune sous l'encre. */
    public static UiColor adaptShadow(UiColor shadow, float luminance) {
        return UiColor.lerp(shadow, new UiColor(255, 255, 255, 0), pageLight);
    }
}
