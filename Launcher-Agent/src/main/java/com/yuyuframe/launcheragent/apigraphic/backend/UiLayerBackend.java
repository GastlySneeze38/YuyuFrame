package com.yuyuframe.launcheragent.apigraphic.backend;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

/**
 * Le calque de transformation du moteur : une opacité, un zoom et un
 * déplacement appliqués à TOUT ce qui est dessiné tant qu'il est ouvert
 * ({@code UiRenderer.pushLayer} / {@code popLayer}).
 *
 * <p>C'est ce qui permet de vraies transitions : un écran entier qui arrive
 * en zoomant, deux écrans en fondu enchaîné, un widget qui apparaît en
 * grandissant — sans qu'aucun widget n'ait à connaître l'animation. Toutes
 * les primitives de la façade finissent ici, donc une seule classe applique
 * la transformation, à toutes les ères.
 *
 * <p>La transformation est affine et uniforme : {@code x' = a·x + bx},
 * {@code y' = a·y + by}, les longueurs (rayons, flou, taille du texte)
 * multipliées par {@code a}, les couleurs par l'opacité. Les calques
 * s'emboîtent (voir {@link #push}). Sans calque ouvert, chaque appel passe
 * tel quel, sans un calcul ni une allocation.
 *
 * <p>Limites assumées : l'opacité ne s'applique pas aux icônes d'objet du jeu
 * ni aux textures de GUI vanilla (leur rendu n'a pas de créneau d'opacité) —
 * elles suivent quand même la position et le zoom.
 */
public final class UiLayerBackend implements UiBackend {

    private final UiBackend inner;

    /** Pile des calques composés : {a, bx, by, alpha} par niveau. */
    private final float[][] stack = new float[16][4];
    private int depth;
    private float a = 1f, bx = 0f, by = 0f, alpha = 1f;

    public UiLayerBackend(UiBackend inner) {
        this.inner = inner;
    }

    public UiBackend inner() {
        return inner;
    }

    /**
     * Ouvre un calque, composé avec celui qui est déjà ouvert : ce qui est
     * dessiné ensuite est zoomé de {@code scale} autour de
     * ({@code pivotX}, {@code pivotY}), décalé de ({@code dx}, {@code dy}) et
     * rendu à {@code opacity} — le tout dans le repère du calque parent.
     */
    public void push(float opacity, float scale, float pivotX, float pivotY, float dx, float dy) {
        if (depth >= stack.length) {
            // Trop d'emboîtements : on n'empile plus, mais on compte, pour que
            // les pop restent appariés.
            depth++;
            return;
        }
        float[] saved = stack[depth];
        saved[0] = a; saved[1] = bx; saved[2] = by; saved[3] = alpha;
        depth++;
        // Calque local : x -> scale·x + pivot·(1 − scale) + d
        float la = scale;
        float lbx = pivotX * (1f - scale) + dx;
        float lby = pivotY * (1f - scale) + dy;
        // Composé avec le parent : parent(local(x)).
        bx = a * lbx + bx;
        by = a * lby + by;
        a = a * la;
        alpha = alpha * Math.max(0f, Math.min(1f, opacity));
    }

    public void pop() {
        if (depth == 0) return;
        depth--;
        if (depth >= stack.length) return;
        float[] saved = stack[depth];
        a = saved[0]; bx = saved[1]; by = saved[2]; alpha = saved[3];
    }

    /** Ferme tous les calques — filet en fin d'image, si un appelant en a laissé un ouvert. */
    public void reset() {
        depth = 0;
        a = 1f; bx = 0f; by = 0f; alpha = 1f;
    }

    public int depth() {
        return depth;
    }

    private boolean identity() {
        return a == 1f && bx == 0f && by == 0f && alpha == 1f;
    }

    /** Rien n'est visible sous ce calque : inutile de dessiner. */
    private boolean invisible() {
        return alpha <= 0.002f;
    }

    private float x(float v) { return a * v + bx; }
    private float y(float v) { return a * v + by; }
    private float len(float v) { return a * v; }
    private UiColor c(UiColor color) { return color == null || alpha >= 1f ? color : color.multiplyAlpha(alpha); }

    // ── Passe-plat ────────────────────────────────────────────────────────

    @Override public String id() { return inner.id(); }
    @Override public void attach(UiRenderer owner, GlBridge gl) { inner.attach(owner, gl); }
    @Override public boolean glassAvailable() { return inner.glassAvailable(); }
    @Override public boolean beginGlassFrame(int passes, int vpWidth, int vpHeight) { return inner.beginGlassFrame(passes, vpWidth, vpHeight); }
    @Override public boolean beginGlassFrame(int passes, int vpWidth, int vpHeight, float[][] regions) { return inner.beginGlassFrame(passes, vpWidth, vpHeight, regions); }
    @Override public boolean beginTextBatch() { return inner.beginTextBatch(); }
    @Override public boolean endTextBatch(int vpWidth, int vpHeight) { return inner.endTextBatch(vpWidth, vpHeight); }
    @Override public boolean vignetteAvailable() { return inner.vignetteAvailable(); }
    @Override public boolean endRoundedClip() { return inner.endRoundedClip(); }
    @Override public void flushVanillaFrame(com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost host, Object hostObject) {
        inner.flushVanillaFrame(host, hostObject);
    }

    // ── Primitives transformées ───────────────────────────────────────────

    @Override
    public boolean text(UiFont font, String content, float px, float py, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (identity()) return inner.text(font, content, px, py, color, scale, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.text(font, content, x(px), y(py), c(color), len(scale), vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2,
                               float rBL, float rBR, float rTL, float rTR,
                               UiColor color, int vpWidth, int vpHeight) {
        if (identity()) return inner.roundedRect(x1, y1, x2, y2, rBL, rBR, rTL, rTR, color, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.roundedRect(x(x1), y(y1), x(x2), y(y2), len(rBL), len(rBR), len(rTL), len(rTR), c(color), vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color, int vpWidth, int vpHeight) {
        if (identity()) return inner.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.roundedRect(x(x1), y(y1), x(x2), y(y2), len(radius), c(color), vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius, UiColor color, int vpWidth, int vpHeight) {
        if (identity()) return inner.roundedRectHud(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.roundedRectHud(x(x1), y(y1), x(x2), y(y2), len(radius), c(color), vpWidth, vpHeight);
    }

    @Override
    public boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (identity()) return inner.vignette(edgeColor, vSize, vpWidth, vpHeight);
        if (invisible()) return true;
        // Plein écran : seule l'opacité a un sens.
        return inner.vignette(c(edgeColor), vSize, vpWidth, vpHeight);
    }

    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float px, float py, float w, float h,
                        float iconAlpha, int vpWidth, int vpHeight) {
        if (identity()) return inner.icon(cacheKey, img, px, py, w, h, iconAlpha, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.icon(cacheKey, img, x(px), y(py), len(w), len(h), iconAlpha * alpha, vpWidth, vpHeight);
    }

    @Override
    public boolean glassPanel(float x1, float y1, float x2, float y2,
                              float rTL, float rTR, float rBL, float rBR,
                              UiColor tint, float tintStrength, UiColor fallback, int vpWidth, int vpHeight) {
        if (identity()) return inner.glassPanel(x1, y1, x2, y2, rTL, rTR, rBL, rBR, tint, tintStrength, fallback, vpWidth, vpHeight);
        if (invisible()) return true;
        // L'opacité du verre est celle de son aplat de repli (voir UiRenderer.drawGlassPanel).
        return inner.glassPanel(x(x1), y(y1), x(x2), y(y2), len(rTL), len(rTR), len(rBL), len(rBR),
            tint, tintStrength, c(fallback), vpWidth, vpHeight);
    }

    @Override
    public boolean blurredPanel(float x1, float y1, float x2, float y2,
                                float rTL, float rTR, float rBL, float rBR,
                                int passes, UiColor tint, float tintStrength, int vpWidth, int vpHeight) {
        if (identity()) return inner.blurredPanel(x1, y1, x2, y2, rTL, rTR, rBL, rBR, passes, tint, tintStrength, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.blurredPanel(x(x1), y(y1), x(x2), y(y2), len(rTL), len(rTR), len(rBL), len(rBR),
            passes, tint, tintStrength, vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRectBatch(float[][] bounds, UiColor[] colors, float radius, int vpWidth, int vpHeight) {
        if (identity()) return inner.roundedRectBatch(bounds, colors, radius, vpWidth, vpHeight);
        if (invisible()) return true;
        float[][] moved = new float[bounds.length][];
        UiColor[] faded = new UiColor[colors.length];
        for (int i = 0; i < bounds.length; i++) {
            float[] b = bounds[i];
            moved[i] = new float[]{ x(b[0]), y(b[1]), x(b[2]), y(b[3]) };
            faded[i] = c(colors[i]);
        }
        return inner.roundedRectBatch(moved, faded, len(radius), vpWidth, vpHeight);
    }

    @Override
    public boolean blendRect(float x1, float y1, float x2, float y2,
                             float rBL, float rBR, float rTL, float rTR,
                             UiColor topColor, com.yuyuframe.launcheragent.apigraphic.value.UiBlendMode mode,
                             int vpWidth, int vpHeight) {
        if (identity()) return inner.blendRect(x1, y1, x2, y2, rBL, rBR, rTL, rTR, topColor, mode, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.blendRect(x(x1), y(y1), x(x2), y(y2), len(rBL), len(rBR), len(rTL), len(rTR),
            c(topColor), mode, vpWidth, vpHeight);
    }

    @Override
    public boolean shadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                          UiColor color, int vpWidth, int vpHeight) {
        if (identity()) return inner.shadow(x1, y1, x2, y2, radius, blur, spread, color, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.shadow(x(x1), y(y1), x(x2), y(y2), len(radius), len(blur), len(spread), c(color), vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                     UiColor color, int vpWidth, int vpHeight) {
        if (identity()) return inner.roundedRectBorder(x1, y1, x2, y2, radius, borderWidth, color, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.roundedRectBorder(x(x1), y(y1), x(x2), y(y2), len(radius), len(borderWidth), c(color), vpWidth, vpHeight);
    }

    @Override
    public boolean gradientRect(float x1, float y1, float x2, float y2, float radius,
                                UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        if (identity()) return inner.gradientRect(x1, y1, x2, y2, radius, colorBottom, colorTop, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.gradientRect(x(x1), y(y1), x(x2), y(y2), len(radius), c(colorBottom), c(colorTop), vpWidth, vpHeight);
    }

    @Override
    public boolean gradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                  UiColor bl, UiColor br, UiColor tl, UiColor tr, int vpWidth, int vpHeight) {
        if (identity()) return inner.gradientRect2D(x1, y1, x2, y2, radius, bl, br, tl, tr, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.gradientRect2D(x(x1), y(y1), x(x2), y(y2), len(radius), c(bl), c(br), c(tl), c(tr), vpWidth, vpHeight);
    }

    @Override
    public boolean multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                         com.yuyuframe.launcheragent.apigraphic.value.UiGradientType type,
                                         float startX, float startY, float endX, float endY,
                                         UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        if (identity()) return inner.multiStopGradientRect(x1, y1, x2, y2, radius, type, startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
        if (invisible()) return true;
        UiColor[] faded = new UiColor[colors.length];
        for (int i = 0; i < colors.length; i++) faded[i] = c(colors[i]);
        return inner.multiStopGradientRect(x(x1), y(y1), x(x2), y(y2), len(radius), type,
            x(startX), y(startY), x(endX), y(endY), faded, positions, vpWidth, vpHeight);
    }

    @Override
    public boolean fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                      UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        if (identity()) return inner.fx(x1, y1, x2, y2, radius, blur, borderWidth, colorA, colorB, gradient, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.fx(x(x1), y(y1), x(x2), y(y2), len(radius), len(blur), len(borderWidth),
            c(colorA), c(colorB), gradient, vpWidth, vpHeight);
    }

    @Override
    public boolean vanillaItemIcon(Object itemStack, float px, float py, float size,
                                   boolean vanillaExtras, int vpWidth, int vpHeight) {
        if (identity()) return inner.vanillaItemIcon(itemStack, px, py, size, vanillaExtras, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.vanillaItemIcon(itemStack, x(px), y(py), len(size), vanillaExtras, vpWidth, vpHeight);
    }

    @Override
    public boolean vanillaGuiBlit(String texturePath, float px, float py, float w, float h,
                                  float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        if (identity()) return inner.vanillaGuiBlit(texturePath, px, py, w, h, u, v, texW, texH, vpWidth, vpHeight);
        if (invisible()) return true;
        return inner.vanillaGuiBlit(texturePath, x(px), y(py), len(w), len(h), u, v, texW, texH, vpWidth, vpHeight);
    }

    @Override
    public boolean beginRoundedClip(float x1, float y1, float x2, float y2, float radius, int vpWidth, int vpHeight) {
        if (identity()) return inner.beginRoundedClip(x1, y1, x2, y2, radius, vpWidth, vpHeight);
        return inner.beginRoundedClip(x(x1), y(y1), x(x2), y(y2), len(radius), vpWidth, vpHeight);
    }
}
