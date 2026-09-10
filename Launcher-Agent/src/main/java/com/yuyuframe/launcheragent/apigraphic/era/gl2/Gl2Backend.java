package com.yuyuframe.launcheragent.apigraphic.era.gl2;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

/**
 * Backend de l'ère gl2 — pipeline fixe (≤ 1.16).
 *
 * <p>Sert le texte ({@code Gl2TextRenderer}) et les six primitives de
 * {@code Gl2PrimitiveRenderer}. Ce qui reste à {@code false} n'est pas « pas
 * encore découpé » mais bel et bien absent de cette ère : le rayon PAR COIN
 * (le shader n'a qu'un {@code u_Radius} uniforme — la façade compose alors
 * l'ancien empilement « rect arrondi + rect plat »), et le rect arrondi
 * spécial HUD, qui n'a de sens que sur Blaze3D.
 */
public final class Gl2Backend implements UiBackend {

    /** Public sans argument : instancié par réflexion depuis {@code UiBackendRegistry}. */
    public Gl2Backend() {}

    private Gl2TextRenderer textRenderer;
    private Gl2PrimitiveRenderer primitives;

    @Override
    public String id() {
        return "gl2";
    }

    @Override
    public void attach(UiRenderer owner, GlBridge gl) {
        this.textRenderer = new Gl2TextRenderer(gl, new FontAtlasTextures(gl));
        this.primitives = new Gl2PrimitiveRenderer(gl);
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                               UiColor color, int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                        int vpWidth, int vpHeight) {
        if (textRenderer == null) return false;
        textRenderer.draw(font, content, x, y, color, scale, vpWidth, vpHeight);
        return true;
    }

    // ── Primitives pas encore découpées ───────────────────────────────────
    // Elles déclinent : l'appelant retombe sur UiPrimitiveRenderer, où le code
    // de cette ère vit encore. Voir render/package-info.java.

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2,
                               float radiusBottomLeft, float radiusBottomRight,
                               float radiusTopLeft, float radiusTopRight,
                               UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius,
                                  UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                      UiColor colorA, UiColor colorB, boolean gradient,
                      int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.fx(x1, y1, x2, y2, radius, blur, borderWidth, colorA, colorB, gradient, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                         com.yuyuframe.launcheragent.apigraphic.value.UiGradientType type,
                                         float startX, float startY, float endX, float endY,
                                         UiColor[] colors, float[] positions,
                                         int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.multiStopGradientRect(x1, y1, x2, y2, radius, type,
            startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean gradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottomLeft, UiColor colorBottomRight,
                                  UiColor colorTopLeft, UiColor colorTopRight,
                                  int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.gradientRect2D(x1, y1, x2, y2, radius,
            colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean vignetteAvailable() {
        return primitives != null && primitives.vignetteAvailable();
    }

    @Override
    public boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.vignette(edgeColor, vSize, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.icon(cacheKey, img, x, y, w, h, alpha, vpWidth, vpHeight);
        return true;
    }
}
