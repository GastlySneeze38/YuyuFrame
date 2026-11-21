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

    /** Début d'une image : les décors redéclarent leurs couvertures. */
    public static void beginFrame() {
        covers.clear();
    }

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
        return lum;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /** L'encre du texte sur fond clair : un noir légèrement violet, comme l'encre du site. */
    public static final UiColor INK = new UiColor(18, 16, 26, 255);

    /** De 0 (fond sombre : la couleur d'origine) à 1 (fond clair : l'encre). Inconnu = 0. */
    public static float lightness(float luminance) {
        if (luminance < 0f) return 0f;
        float t = Math.max(0f, Math.min(1f, (luminance - LIGHT_FROM) / (LIGHT_TO - LIGHT_FROM)));
        return t * t * (3f - 2f * t);
    }

    /** Bornes du passage blanc → noir : un fond franchement clair seulement. */
    private static final float LIGHT_FROM = 0.42f, LIGHT_TO = 0.58f;

    /**
     * La couleur d'un texte posé sur ce fond. Sur fond sombre, celle
     * d'origine. Sur fond clair : un gris ou un blanc devient l'encre (un
     * texte secondaire un peu moins opaque que le principal, pour garder la
     * hiérarchie) ; une couleur (le vert d'un compteur) est assombrie.
     */
    public static UiColor adapt(UiColor color, float luminance) {
        float t = lightness(luminance);
        if (t <= 0f || color == null) return color;
        float max = Math.max(color.r, Math.max(color.g, color.b));
        float min = Math.min(color.r, Math.min(color.g, color.b));
        UiColor onLight;
        if (max - min < 0.15f) {
            float l = (max + min) / 2f;
            onLight = INK.withAlpha(color.a * (0.62f + 0.38f * l));
        } else {
            onLight = new UiColor(Math.round(color.r * 255 * 0.5f), Math.round(color.g * 255 * 0.5f),
                Math.round(color.b * 255 * 0.5f), Math.round(color.a * 255));
        }
        return UiColor.lerp(color, onLight, t);
    }

    /** L'ombre d'un texte sur ce fond : sombre sur fond sombre, à peine un halo clair sur fond clair. */
    public static UiColor adaptShadow(UiColor shadow, float luminance) {
        float t = lightness(luminance);
        if (t <= 0f) return shadow;
        return UiColor.lerp(shadow, new UiColor(255, 255, 255, 40), t);
    }
}
