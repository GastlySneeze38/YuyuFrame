package com.yuyuframe.launcheragent.apigraphic.era.gl3;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

/**
 * Backend de l'ère gl3 — Core Profile 3.2 (1.17 – 1.21.x).
 *
 * <p>Ne sert pour l'instant que le TEXTE, comme {@code Gl2Backend} : c'est la
 * seule primitive découpée. Le reste retombe sur {@code UiPrimitiveRenderer}.
 */
public final class Gl3Backend implements UiBackend {

    /** Public sans argument : instancié par réflexion depuis {@code UiBackendRegistry}. */
    public Gl3Backend() {}

    private Gl3TextRenderer textRenderer;

    @Override
    public String id() {
        return "gl3";
    }

    @Override
    public void attach(UiRenderer owner, GlBridge gl) {
        this.textRenderer = new Gl3TextRenderer(owner, gl, new FontAtlasTextures(gl));
    }

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                               UiColor color, int vpWidth, int vpHeight) {
        return false;   // pas encore découpé — voir render/package-info.java
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
    public boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        return false;
    }

    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        return false;
    }
}
