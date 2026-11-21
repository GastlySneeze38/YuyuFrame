package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Le texte de l'interface : couper avec « ... », couper aux mots, centrer.
 * Chaque écran avait sa propre copie de ces trois gestes ; ils vivent ici.
 *
 * <p>« ... » et non le caractère unique « … » : la police du jeu ne l'a pas
 * (il s'affichait « ? »).
 */
public final class UiText {
    private UiText() {}

    public static final String ELLIPSIS = "...";

    private static UiFont font(UiFont f) { return f == null ? UiFont.REGULAR : f; }

    public static float width(UiFont font, String text, float scale) {
        return font(font).textWidth(text, scale);
    }

    /** {@code text} raccourci avec « ... » pour tenir dans {@code maxW} ({@code font} nul = police normale). */
    public static String fit(UiFont font, String text, float maxW, float scale) {
        if (text == null) return "";
        if (width(font, text, scale) <= maxW) return text;
        String s = text;
        while (s.length() > 1 && width(font, s + ELLIPSIS, scale) > maxW) s = s.substring(0, s.length() - 1);
        return s.trim() + ELLIPSIS;
    }

    /** Lignes de {@code text} coupé aux mots dans {@code maxW} ; la dernière permise finit par « ... » s'il en reste. */
    public static List<String> wrap(UiFont font, String text, float maxW, float scale, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width(font, candidate, scale) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            cut.set(maxLines - 1, fit(font, cut.get(maxLines - 1) + " " + ELLIPSIS, maxW, scale));
            return cut;
        }
        return lines;
    }

    /** Texte coupé aux mots, la première ligne à {@code baseline}, les suivantes {@code lineH} plus bas. */
    public static void drawWrapped(UiRenderer r, UiFont font, String text, float x, float baseline, float maxW,
                                   int maxLines, float lineH, UiColor color, float scale, int vw, int vh) {
        List<String> lines = wrap(font, text, maxW, scale, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            r.drawText(font(font), lines.get(i), x, baseline - i * lineH, color, scale, vw, vh);
        }
    }

    /**
     * Ligne de base qui centre visuellement une ligne de texte dans une boîte
     * de hauteur {@code h} commençant à {@code y} (repère Y montant).
     */
    public static float centeredBaseline(UiFont font, float y, float h, float scale) {
        UiFont f = font(font);
        float cs = scale * UiFont.SIZE_CORRECTION;
        return y + h / 2f - (f.ascent - f.descent) * cs / 2f;
    }

    /** Hauteur d'une ligne (voir {@link UiLayout#lineHeight}). */
    public static float lineHeight(UiFont font, float scale) {
        return UiLayout.lineHeight(font, scale);
    }

    /** Texte centré horizontalement dans {@code [x, x + w]}. */
    public static void drawCentered(UiRenderer r, UiFont font, String text, float x, float w, float baseline,
                                    UiColor color, float scale, int vw, int vh) {
        float tw = width(font, text, scale);
        r.drawText(font(font), text, x + (w - tw) / 2f, baseline, color, scale, vw, vh);
    }

    /**
     * Texte posé sur le décor (pas sur du verre) : il suit la clarté du fond
     * sous lui (2026-11-21, {@link UiBackdropTone}) — blanc avec une ombre
     * sur un fond sombre, l'encre sur un fond clair.
     */
    public static void drawOnBackdrop(UiRenderer r, UiFont font, String text, float x, float baseline,
                                      UiColor color, float scale, int vw, int vh) {
        float lum = backdropLuminance(font, text, x, baseline, scale, vw, vh);
        r.drawTextShadowed(font(font), text, x, baseline, UiBackdropTone.adapt(color, lum),
            UiBackdropTone.adaptShadow(UiTheme.TEXT_SHADOW, lum), scale, scale, scale, vw, vh);
    }

    /** La clarté du fond sous une ligne de texte (voir {@link UiBackdropTone#luminance}). */
    public static float backdropLuminance(UiFont font, String text, float x, float baseline, float scale, int vw, int vh) {
        float lineH = lineHeight(font, scale);
        return UiBackdropTone.luminance(x, baseline - lineH * 0.2f, x + width(font, text, scale), baseline + lineH * 0.8f, vw, vh);
    }
}
