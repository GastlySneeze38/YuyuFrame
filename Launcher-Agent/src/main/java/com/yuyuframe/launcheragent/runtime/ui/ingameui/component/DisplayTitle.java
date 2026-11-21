package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch;
import com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les grands titres posés sur le décor (« Modules », « Amis »…), dans la
 * police des titres du site (Unbounded ExtraBold, {@code /fonts/}, licence
 * OFL à côté) — 2026-11-21.
 *
 * <p>Comme sur le site ({@code .display.doubled}) : le texte plein, et
 * derrière lui son contour décalé d'un vingtième de lettre. À l'entrée
 * d'une page ({@link #replay}), le contour arrive après le texte, d'un peu
 * plus loin, et se pose.
 *
 * <p>Le titre suit la clarté du fond ({@link UiBackdropTone}) : blanc sur
 * un fond sombre, l'encre sur un fond clair.
 *
 * <p>Les lettres ne passent pas par l'atlas SDF du moteur : la police
 * n'existe qu'ici, pour quelques mots. Chaque titre est dessiné une fois par
 * Java2D en blanc, à deux fois sa taille d'affichage pour rester net, et
 * teinté au dessin (couleur par sommet).
 */
public final class DisplayTitle {
    private DisplayTitle() {}

    private static final String FONT = "/fonts/Unbounded-ExtraBold.ttf";
    /** Résolution des images : deux pixels d'image par pixel d'écran. */
    private static final float RS = 2f;
    /** Décalage du contour, en part de la taille de la police (le site : 0.055em). */
    private static final float OFFSET_EM = 0.055f, ENTER_OFFSET_EM = 0.2f;
    /** Entrée du contour : il suit le texte de peu, et se pose sans rebond. */
    private static final float ENTER_DELAY_S = 0.12f, ENTER_S = 0.6f;

    private static Font base;
    private static boolean failed;

    /** Une image de titre : blanche, avec sa ligne de base et sa marge de gauche (pixels d'image). */
    private static final class Glyphs {
        final BufferedImage image;
        final String key;
        final float baselineFromTop, padLeft, advance;

        Glyphs(BufferedImage image, String key, float baselineFromTop, float padLeft, float advance) {
            this.image = image;
            this.key = key;
            this.baselineFromTop = baselineFromTop;
            this.padLeft = padLeft;
            this.advance = advance;
        }
    }

    private static final Map<String, Glyphs> CACHE = new LinkedHashMap<String, Glyphs>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Glyphs> eldest) {
            return size() > 24;
        }
    };

    private static Font font() {
        if (base != null || failed) return base;
        try (InputStream in = DisplayTitle.class.getResourceAsStream(FONT)) {
            if (in == null) throw new IllegalStateException("ressource absente : " + FONT);
            base = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Throwable t) {
            failed = true;
            LauncherLog.err("[DisplayTitle] police des titres illisible : repli sur la police de l'interface", t);
        }
        return base;
    }

    /** Taille de la police d'un grand titre de page. */
    public static float pageSize() {
        return UiTheme.px(54f);
    }

    /** Taille de la police d'un titre de section (« Favoris », « Hors ligne »). */
    public static float sectionSize() {
        return UiTheme.px(26f);
    }

    /** Largeur du titre à cette taille. */
    public static float width(String text, float size) {
        Glyphs g = glyphs(text, size, false);
        return g == null ? 0f : g.advance / RS;
    }

    /** Départ de l'entrée du contour, remis à zéro à l'ouverture d'une page. */
    private static long enteredAt = System.nanoTime();

    /** Une page s'ouvre : le contour de son titre rejoue son arrivée. */
    public static void replay() {
        enteredAt = System.nanoTime();
    }

    /**
     * Un grand titre doublé de son contour, ligne de base à {@code baseline}.
     * La police manque : le titre passe par la police de l'interface.
     */
    public static void drawPage(UiRenderer r, String text, float x, float baseline, float size, int vw, int vh) {
        draw(r, text, x, baseline, size, true, vw, vh);
    }

    /** Un titre de section, plein, sans contour. */
    public static void drawSection(UiRenderer r, String text, float x, float baseline, float size, int vw, int vh) {
        draw(r, text, x, baseline, size, false, 1f, vw, vh);
    }

    /** Un titre de section à cette opacité (le fondu au bord d'une liste qui défile). */
    public static void drawSection(UiRenderer r, String text, float x, float baseline, float size, float alpha, int vw, int vh) {
        draw(r, text, x, baseline, size, false, alpha, vw, vh);
    }

    private static void draw(UiRenderer r, String text, float x, float baseline, float size, boolean doubled, int vw, int vh) {
        draw(r, text, x, baseline, size, doubled, 1f, vw, vh);
    }

    private static void draw(UiRenderer r, String text, float x, float baseline, float size, boolean doubled, float alpha,
                             int vw, int vh) {
        if (text == null || text.isEmpty() || alpha <= 0.003f) return;
        Glyphs fill = glyphs(text, size, false);
        if (fill == null) {
            UiText.drawOnBackdrop(r, com.yuyuframe.launcheragent.apigraphic.value.UiFont.BOLD, text, x, baseline,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), size / 32f, vw, vh);
            return;
        }
        float w = fill.advance / RS;
        float lum = UiBackdropTone.luminance(x, baseline - size * 0.1f, x + w, baseline + size * 0.75f, vw, vh);
        UiColor color = UiBackdropTone.adapt(UiTheme.TEXT_PRIMARY, lum).multiplyAlpha(alpha);
        if (doubled) {
            Glyphs outline = glyphs(text, size, true);
            if (outline != null) {
                float t = (System.nanoTime() - enteredAt) / 1e9f - ENTER_DELAY_S;
                float p = UiEasing.UI_OUT.apply(Math.max(0f, Math.min(1f, t / ENTER_S)));
                float off = size * (OFFSET_EM + (ENTER_OFFSET_EM - OFFSET_EM) * (1f - p));
                // y vers le haut : le contour descend à droite, comme sur le site.
                quad(r, outline, x + off, baseline - off, color.multiplyAlpha(p), vw, vh);
            }
        }
        quad(r, fill, x, baseline, color, vw, vh);
    }

    private static void quad(UiRenderer r, Glyphs g, float x, float baseline, UiColor color, int vw, int vh) {
        if (color.a <= 0.003f) return;
        float iw = g.image.getWidth() / RS, ih = g.image.getHeight() / RS;
        float left = x - g.padLeft / RS;
        float bottom = baseline - (g.image.getHeight() - g.baselineFromTop) / RS;
        int c = argb(color);
        UiMeshBatch batch = new UiMeshBatch();
        // Sens inverse des aiguilles d'une montre, y vers le haut ; v = 0 en haut de l'image.
        batch.add(left, bottom, left + iw, bottom, left + iw, bottom + ih, left, bottom + ih,
            0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, c, c, c, c);
        r.drawTexturedQuads(g.key, g.image, batch, vw, vh);
    }

    private static int argb(UiColor c) {
        int a = Math.round(Math.max(0f, Math.min(1f, c.a)) * 255f);
        return (a << 24) | (Math.round(c.r * 255f) << 16) | (Math.round(c.g * 255f) << 8) | Math.round(c.b * 255f);
    }

    /** L'image blanche d'un titre (plein ou contour), faite une fois. */
    private static Glyphs glyphs(String text, float size, boolean outline) {
        Font f = font();
        if (f == null) return null;
        int pxSize = Math.max(8, Math.round(size * RS));
        String key = "yf-title:" + (outline ? "o:" : "f:") + pxSize + ":" + text;
        Glyphs cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            Font sized = f.deriveFont((float) pxSize);
            FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
            GlyphVector gv = sized.createGlyphVector(frc, text);
            // Lettres resserrées comme sur le site (letter-spacing: -0.03em).
            float track = -0.03f * pxSize;
            for (int i = 1; i < gv.getNumGlyphs(); i++) {
                java.awt.geom.Point2D p = gv.getGlyphPosition(i);
                gv.setGlyphPosition(i, new java.awt.geom.Point2D.Float((float) p.getX() + track * i, (float) p.getY()));
            }
            Shape shape = gv.getOutline();
            Rectangle2D bounds = shape.getBounds2D();
            float stroke = Math.max(1.5f, pxSize * 0.028f);
            int pad = (int) Math.ceil(stroke) + 3;
            int w = (int) Math.ceil(bounds.getWidth()) + pad * 2;
            int h = (int) Math.ceil(bounds.getHeight()) + pad * 2;
            BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setColor(java.awt.Color.WHITE);
                g.translate(pad - bounds.getX(), pad - bounds.getY());
                if (outline) {
                    g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                } else {
                    g.fill(shape);
                }
            } finally {
                g.dispose();
            }
            // Ligne de base (y = 0 du dessin) dans l'image, et ce qui dépasse à gauche de l'origine.
            float baselineFromTop = (float) (pad - bounds.getY());
            float padLeft = (float) (pad - bounds.getX());
            // La largeur dessinée (le bord droit de la dernière lettre) : c'est elle qui compte pour poser ce qui suit.
            float advance = (float) bounds.getMaxX();
            Glyphs glyphs = new Glyphs(img, key, baselineFromTop, padLeft, advance);
            CACHE.put(key, glyphs);
            return glyphs;
        } catch (Throwable t) {
            failed = true;
            LauncherLog.err("[DisplayTitle] titre « " + text + " » non dessiné", t);
            return null;
        }
    }
}
