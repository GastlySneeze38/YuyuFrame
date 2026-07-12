package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

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
 * ressource, pas de lib externe (pas de STB TrueType/FreeType).
 *
 * Rasterisée en couverture anti-aliasée classique PUIS convertie en champ de
 * distance signée (SDF, voir {@link #buildSignedDistanceField}) — l'alpha de
 * chaque texel encode "à quelle distance du bord du glyphe" plutôt que
 * "opaque ou pas". UiRenderer.drawText utilise un shader dédié qui
 * reconstruit un bord net à partir de cette distance (smoothstep+fwidth),
 * quelle que soit l'échelle d'affichage — contrairement à un simple
 * bilinéaire/mipmap sur de la couverture brute, qui amincissait de façon
 * incohérente les traits fins d'une même lettre (observé en test : certaines
 * parties d'une lettre moins opaques que d'autres, signature classique du
 * moyennage de mipmap sur du texte bitmap classique).
 *
 * Charset couvert : ASCII imprimable (32-126) + Latin-1 Supplement
 * (160-255, couvre é/è/ê/à/ç/ù/ô/î etc.) + Œ/œ (U+0152/U+0153, hors Latin-1)
 * — suffisant pour l'anglais et le français.
 */
public final class UiFont {

    /**
     * Résolution RÉELLE de rasterisation de l'atlas — un pur curseur de
     * QUALITÉ, libre de monter (plus de détail source pour le filtrage
     * trilinéaire, voir UiRenderer.ensureFontTexture) SANS jamais affecter la
     * taille affichée : voir {@link #SIZE_CORRECTION}, qui compense
     * automatiquement dans UiRenderer.drawText. Avant cette séparation,
     * monter cette constante grossissait aussi le texte à l'écran (bug vécu
     * en test : personne n'avait besoin de retoucher les dizaines de
     * scale=... éparpillés dans les écrans, et pourtant la taille changeait).
     */
    public static final float RASTER_PX = 64f;

    /**
     * Taille de référence à laquelle TOUS les appels drawText(...,scale,...)
     * existants dans le code sont calibrés — NE JAMAIS CHANGER cette valeur
     * (ce serait rechanger la taille affichée partout). Pour une meilleure
     * qualité, monter {@link #RASTER_PX} à la place.
     */
    public static final float REFERENCE_PX = 32f;

    /** Facteur appliqué par UiRenderer pour que RASTER_PX reste invisible côté taille affichée. */
    public static final float SIZE_CORRECTION = REFERENCE_PX / RASTER_PX;

    /**
     * Largeur (en pixels RASTER_PX) de la zone de transition du champ de
     * distance signée de part et d'autre du bord réel du glyphe — au-delà,
     * la valeur sature à 0 (pleinement "dehors") ou 255 (pleinement
     * "dedans"). Détermine aussi la marge minimale nécessaire autour de
     * chaque glyphe dans l'atlas (voir padding) pour qu'un glyphe voisin ne
     * soit jamais lu avant saturation complète.
     */
    private static final float SDF_SPREAD = 8f;

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
    private final BufferedImage atlasImagePlain;
    public final int ascent, descent, cellHeight;
    private final Glyph fallback;

    private UiFont(int style) {
        Font font = new Font(Font.SANS_SERIF, style, Math.round(RASTER_PX));

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
        // Marge >= 2x SDF_SPREAD : le champ de distance signée doit avoir
        // pleinement saturé (0 ou 255) avant d'atteindre la cellule voisine,
        // sinon un mipmap ou un échantillonnage bilinéaire au bord pourrait
        // mélanger deux glyphes différents.
        int padding = 16;
        int atlasW = 1024;
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

        // Copie profonde AVANT la transformation SDF (qui mute atlasImage en
        // place, voir buildSignedDistanceField) : le pipeline Blaze3D natif
        // era E (UiTextBlaze3D) utilise le shader vanilla RenderPipelines.GUI_TEXT
        // (core/rendertype_text), qui fait un simple texture.rgba × couleur —
        // AUCUN seuillage SDF. Lui donner l'atlas SDF (alpha ~128 sur toute la
        // zone de transition, saturé seulement loin du bord) produit un rendu
        // quasi invisible sans jamais lever d'exception — exactement le
        // symptôme observé après que tout le reste de la chaîne (texture,
        // buffer, render pass, uniforms, draw indexé) ait été validé sans
        // erreur. atlasImage() (SDF) reste inchangé pour le pipeline SDF
        // existant (brackets 1.8.9→1.21.4) — ne JAMAIS le faire pointer ici.
        atlasImagePlain = new BufferedImage(
            atlasImage.getColorModel(), atlasImage.copyData(null), atlasImage.isAlphaPremultiplied(), null);

        buildSignedDistanceField(atlasImage);

        fallback = glyphs.get('?');
    }

    /**
     * Remplace en place le canal alpha de l'atlas (couverture anti-aliasée
     * classique, 0-255) par un champ de distance signée : pour chaque texel,
     * la distance (en pixels, saturée à ±{@link #SDF_SPREAD}) au bord le plus
     * proche du glyphe, positive à l'intérieur, négative à l'extérieur,
     * remappée en 0-255 (128 = exactement sur le bord). RGB inchangé (blanc,
     * inutilisé — voir UiRenderer, le shader de texte ne lit QUE l'alpha).
     *
     * Transformée de distance chamfer 2-passes (approximation quasi-
     * euclidienne classique, poids 1/√2) — O(largeur×hauteur), calculée une
     * seule fois au chargement de l'agent, jamais par frame.
     */
    private static void buildSignedDistanceField(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);

        boolean[] inside = new boolean[w * h];
        boolean[] outside = new boolean[w * h];
        for (int i = 0; i < argb.length; i++) {
            boolean in = ((argb[i] >>> 24) & 0xFF) >= 128;
            inside[i] = in;
            outside[i] = !in;
        }

        float[] distToOutside = chamferTransform(outside, w, h); // vu depuis l'intérieur : distance pour sortir
        float[] distToInside = chamferTransform(inside, w, h);   // vu depuis l'extérieur : distance pour entrer

        for (int i = 0; i < argb.length; i++) {
            float signed = inside[i] ? distToOutside[i] : -distToInside[i];
            float norm = Math.max(-1f, Math.min(1f, signed / SDF_SPREAD));
            int a = Math.round((norm * 0.5f + 0.5f) * 255f);
            argb[i] = (a << 24) | 0x00FFFFFF;
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
    }

    /** Distance (chamfer, 2 passes) de chaque texel au texel "seed" (true) le plus proche. */
    private static float[] chamferTransform(boolean[] seed, int w, int h) {
        final float INF = 1e6f;
        final float SQRT2 = 1.41421356f;
        float[] dist = new float[w * h];
        for (int i = 0; i < dist.length; i++) dist[i] = seed[i] ? 0f : INF;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                float d = dist[idx];
                if (x > 0) d = Math.min(d, dist[idx - 1] + 1f);
                if (y > 0) {
                    d = Math.min(d, dist[idx - w] + 1f);
                    if (x > 0) d = Math.min(d, dist[idx - w - 1] + SQRT2);
                    if (x < w - 1) d = Math.min(d, dist[idx - w + 1] + SQRT2);
                }
                dist[idx] = d;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            int row = y * w;
            for (int x = w - 1; x >= 0; x--) {
                int idx = row + x;
                float d = dist[idx];
                if (x < w - 1) d = Math.min(d, dist[idx + 1] + 1f);
                if (y < h - 1) {
                    d = Math.min(d, dist[idx + w] + 1f);
                    if (x < w - 1) d = Math.min(d, dist[idx + w + 1] + SQRT2);
                    if (x > 0) d = Math.min(d, dist[idx + w - 1] + SQRT2);
                }
                dist[idx] = d;
            }
        }
        return dist;
    }

    public BufferedImage atlasImage() { return atlasImage; }

    /** Atlas AVANT transformation SDF (couverture antialiasée classique) — voir UiTextBlaze3D, seul consommateur. */
    public BufferedImage atlasImagePlain() { return atlasImagePlain; }

    public Glyph glyph(char c) {
        Glyph g = glyphs.get(c);
        return g != null ? g : fallback;
    }

    public float textWidth(String text, float scale) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += glyph(text.charAt(i)).advance;
        return w * scale * SIZE_CORRECTION;
    }

    /** Hauteur de ligne complète (ascent+descent, mise à l'échelle) — pour l'espacement entre lignes. */
    public float lineHeight(float scale) {
        return (ascent + descent) * scale * SIZE_CORRECTION;
    }
}
