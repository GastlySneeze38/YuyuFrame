package com.yuyuframe.launcheragent.runtime.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Police bitmap générée à la volée via java.awt (AWT est toujours présent
 * dans le JVM hôte, Minecraft ou pas) — pas d'atlas/police embarquée en
 * ressource, pas de lib externe (pas de STB TrueType/FreeType). Rasterisée
 * une seule fois par style (REGULAR/BOLD) à {@link #BASE_PX} pixels puis mise
 * à l'échelle au dessin (voir UiRenderer.drawText) : rendre plus grand que la
 * taille d'affichage typique (13-22px) donne un antialiasing plus net qu'un
 * rendu direct à la taille cible.
 *
 * Charset couvert : ASCII imprimable (32-126) + Latin-1 Supplement
 * (160-255, couvre é/è/ê/à/ç/ù/ô/î etc.) + Œ/œ (U+0152/U+0153, hors Latin-1)
 * — suffisant pour l'anglais et le français.
 */
public final class UiFont {

    /** Taille de rasterisation de l'atlas — les appelants dessinent avec un scale relatif à ceci. */
    public static final float BASE_PX = 32f;

    public static final UiFont REGULAR = new UiFont(Font.PLAIN);
    public static final UiFont BOLD = new UiFont(Font.BOLD);

    public static final class Glyph {
        public final float u0, v0, u1, v1;
        public final int width;
        public final int advance;

        Glyph(float u0, float v0, float u1, float v1, int width, int advance) {
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            this.width = width; this.advance = advance;
        }
    }

    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private final BufferedImage atlasImage;
    public final int ascent, descent, cellHeight;
    private final Glyph fallback;

    private UiFont(int style) {
        Font font = new Font(Font.SANS_SERIF, style, Math.round(BASE_PX));

        BufferedImage sizer = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sizer.createGraphics();
        sg.setFont(font);
        FontMetrics fm = sg.getFontMetrics();
        this.ascent = fm.getAscent();
        this.descent = fm.getDescent();
        // +4 de marge verticale : évite qu'un antialiasing agressif sur les
        // descentes (g, y, ...) déborde sur la cellule de la ligne suivante.
        this.cellHeight = ascent + descent + 4;

        List<Character> chars = new ArrayList<>();
        for (int cp = 32; cp <= 126; cp++) chars.add((char) cp);
        for (int cp = 160; cp <= 255; cp++) chars.add((char) cp);
        chars.add((char) 338); // Œ
        chars.add((char) 339); // œ

        // Shelf packing simple : largeur d'atlas fixe, on avance en X puis on
        // saute de ligne (hauteur de cellule fixe = cellHeight) quand ça déborde.
        int padding = 2;
        int atlasW = 512;
        int cursorX = padding, cursorY = padding;
        Map<Character, int[]> placement = new HashMap<>();
        for (char c : chars) {
            int w = fm.charWidth(c);
            if (w <= 0) w = fm.charWidth(' ');
            if (cursorX + w + padding > atlasW) {
                cursorX = padding;
                cursorY += cellHeight + padding;
            }
            placement.put(c, new int[]{cursorX, cursorY, w});
            cursorX += w + padding;
        }
        sg.dispose();

        int neededH = cursorY + cellHeight + padding;
        int atlasH = 64;
        while (atlasH < neededH) atlasH *= 2; // arrondi à la puissance de 2 sup. — compat GPU anciens

        atlasImage = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlasImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE); // blanc uni — la teinte réelle vient du glColor4f au dessin (GL_MODULATE)

        for (Map.Entry<Character, int[]> e : placement.entrySet()) {
            char c = e.getKey();
            int[] p = e.getValue();
            g.drawString(String.valueOf(c), p[0], p[1] + ascent);
            glyphs.put(c, new Glyph(
                p[0] / (float) atlasW, p[1] / (float) atlasH,
                (p[0] + p[2]) / (float) atlasW, (p[1] + cellHeight) / (float) atlasH,
                p[2], p[2]
            ));
        }
        g.dispose();

        fallback = glyphs.get('?');
    }

    public BufferedImage atlasImage() { return atlasImage; }

    public Glyph glyph(char c) {
        Glyph g = glyphs.get(c);
        return g != null ? g : fallback;
    }

    public float textWidth(String text, float scale) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += glyph(text.charAt(i)).advance * scale;
        return w;
    }

    /** Hauteur de ligne complète (ascent+descent, mise à l'échelle) — pour l'espacement entre lignes. */
    public float lineHeight(float scale) {
        return (ascent + descent) * scale;
    }
}
