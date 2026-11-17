package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Les anneaux de progression et les losanges des paliers, dessinés une fois
 * en image (Java2D) à leur taille exacte puis gardés : le moteur n'a pas de
 * primitive pour un arc ou un carré tourné.
 */
final class MissionShapes {
    private MissionShapes() {}

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private static Color awt(UiColor c, float alpha) {
        return new Color(c.r, c.g, c.b, alpha);
    }

    /** Anneau de {@code n} pixels rempli à {@code ratio} (0..1), accent sur piste claire, depuis le haut dans le sens horaire. */
    static BufferedImage ring(int n, float ratio) {
        String key = "ring:" + n + ":" + Math.round(ratio * 100f) + ":" + UiTheme.ACCENT.hashCode();
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float stroke = Math.max(2f, n * 0.13f);
            float inset = stroke / 2f + 0.5f;
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, 28));
            g.draw(new Ellipse2D.Float(inset, inset, n - inset * 2f, n - inset * 2f));
            if (ratio > 0f) {
                g.setColor(awt(UiTheme.ACCENT, 1f));
                g.draw(new Arc2D.Float(inset, inset, n - inset * 2f, n - inset * 2f, 90f, -360f * ratio, Arc2D.OPEN));
            }
        } finally {
            g.dispose();
        }
        CACHE.put(key, img);
        return img;
    }

    /** Losange d'un palier : rempli d'accent une fois atteint, simple contour sinon. */
    static BufferedImage diamond(int n, boolean filled) {
        String key = "diamond:" + n + ":" + filled + ":" + UiTheme.ACCENT.hashCode();
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float stroke = Math.max(2f, n * 0.06f);
            float c = n / 2f, r = n / 2f - stroke - n * 0.06f;
            Path2D.Float path = new Path2D.Float();
            path.moveTo(c, c - r);
            path.lineTo(c + r, c);
            path.lineTo(c, c + r);
            path.lineTo(c - r, c);
            path.closePath();
            g.setColor(filled ? awt(UiTheme.ACCENT, 0.32f) : new Color(255, 255, 255, 6));
            g.fill(path);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(filled ? awt(UiTheme.ACCENT, 1f) : new Color(255, 255, 255, 40));
            g.draw(path);
        } finally {
            g.dispose();
        }
        CACHE.put(key, img);
        return img;
    }
}
