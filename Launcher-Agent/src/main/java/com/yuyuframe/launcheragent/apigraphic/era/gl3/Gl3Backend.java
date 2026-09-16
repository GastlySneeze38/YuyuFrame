package com.yuyuframe.launcheragent.apigraphic.era.gl3;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.backend.UiBackend;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.item.Gl3VanillaItemRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3Backdrop;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3Blend;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3Blur;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3Icon;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3PrimitiveRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3Rect;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3TextBatch;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.pass.Gl3TextRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlRoundedClip;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlSrgbDiagnostic;
import com.yuyuframe.launcheragent.apigraphic.value.UiBlendMode;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;

/**
 * Backend de l'ère gl3 — shaders GLSL 150 et VAO/VBO : 1.17 – 1.21.x en Core
 * Profile, 1.8.9 en contexte 3.2 de compatibilité (couche LWJGL 3).
 *
 * <h2>Arborescence (2026-09-14, sur le modèle de {@code era/blaze3d})</h2>
 *
 * <ul>
 *   <li>{@code pass/} — ce qui dessine :
 *     <ul>
 *       <li>{@link Gl3PrimitiveRenderer} : rect arrondi uniforme, vignette,
 *           icône, dégradés, FX (ombre, contour, lueur) ;</li>
 *       <li>{@link Gl3TextRenderer} : texte SDF, une chaîne à la fois ;</li>
 *       <li>{@link Gl3Rect} : rayon par coin et rects en lot ;</li>
 *       <li>{@link Gl3TextBatch} : lot de texte ;</li>
 *       <li>{@link Gl3Blur} : verre dépoli et panneau flouté ;</li>
 *       <li>{@link Gl3Blend} : modes de fusion ;</li>
 *       <li>{@link Gl3Icon} + {@code Gl3IconAtlas} : icônes depuis l'atlas partagé ;</li>
 *       <li>{@link Gl3Backdrop}, {@code Gl3Core}, {@code Gl3VertexStream} :
 *           infrastructure partagée (copie du fond, programmes, sommets) ;</li>
 *     </ul>
 *   </li>
 *   <li>{@code item/} — pont vers le rendu d'items vanilla
 *       ({@link Gl3VanillaItemRenderer}).</li>
 * </ul>
 *
 * <h2>Parité avec Blaze3D</h2>
 *
 * Rayon par coin, verre dépoli, modes de fusion, lot de texte et rects en lot
 * sont servis depuis le 2026-09-14. Reste propre à Blaze3D : le rect « HUD »
 * inséré dans la passe GUI vanilla ({@link #roundedRectHud}, qui décline ici
 * et retombe sur le rect normal). Reste propre à gl3 : le clip arrondi. Ombre,
 * contour et lueur ne sont dessinés sur AUCUNE des deux ères (voir
 * {@link #shadow}).
 */
public final class Gl3Backend implements UiBackend {

    /** Public sans argument : instancié par réflexion depuis {@code UiBackendRegistry}. */
    public Gl3Backend() {}

    private Gl3TextRenderer textRenderer;
    private Gl3TextBatch textBatch;
    private Gl3PrimitiveRenderer primitives;
    private Gl3Rect rects;
    private Gl3Icon icons;
    private Gl3Blur blur;
    private Gl3Blend blend;
    private GlRoundedClip roundedClip;
    /** Pas de GL requis (file + réflexion) : créé tout de suite, pas dans {@link #attach}. */
    private final Gl3VanillaItemRenderer vanillaItems = new Gl3VanillaItemRenderer();

    @Override
    public String id() {
        return "gl3";
    }

    @Override
    public void attach(UiRenderer owner, GlBridge gl) {
        FontAtlasTextures fonts = new FontAtlasTextures(gl);
        Gl3Backdrop backdrop = new Gl3Backdrop();
        this.textRenderer = new Gl3TextRenderer(owner, gl, fonts);
        this.textBatch = new Gl3TextBatch(fonts);
        this.primitives = new Gl3PrimitiveRenderer(owner, gl);
        this.rects = new Gl3Rect();
        this.icons = new Gl3Icon();
        this.blur = new Gl3Blur(backdrop);
        this.blend = new Gl3Blend(backdrop);
        this.roundedClip = new GlRoundedClip(gl);
        GlSrgbDiagnostic.logOnce(gl, "gl3");
    }

    // ── Pont vers les renderers du jeu ────────────────────────────────────

    @Override
    public boolean vanillaItemIcon(Object itemStack, float x, float y, float size,
                                   boolean vanillaExtras, int vpWidth, int vpHeight) {
        if (itemStack == null) return true;
        vanillaItems.enqueueItemIcon(itemStack, x, y, size, vanillaExtras, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean vanillaGuiBlit(String texturePath, float x, float y, float w, float h,
                                  float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        vanillaItems.enqueueGuiBlit(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        return true;
    }

    /** Seul hôte de CETTE ère : le hub en pipeline fixe de la 1.8.9 ({@code LEGACY_HUD}). */
    @Override
    public void flushVanillaFrame(VanillaFlushHost host, Object hostObject) {
        if (hostObject == null) return;
        if (host == VanillaFlushHost.LEGACY_HUD) vanillaItems.flushLegacyHud();
    }

    // ── Rects ─────────────────────────────────────────────────────────────

    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                               UiColor color, int vpWidth, int vpHeight) {
        if (primitives == null) return false;
        primitives.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
        return true;
    }

    /**
     * Rayon par coin EXACT ({@link Gl3Rect}). {@code false} seulement si son
     * programme ne compile pas : la façade reprend alors son empilement
     * « rect au rayon max + bandes plates ».
     */
    @Override
    public boolean roundedRect(float x1, float y1, float x2, float y2,
                               float radiusBottomLeft, float radiusBottomRight,
                               float radiusTopLeft, float radiusTopRight,
                               UiColor color, int vpWidth, int vpHeight) {
        return rects != null && rects.draw(x1, y1, x2, y2,
            radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight, color, vpWidth, vpHeight);
    }

    @Override
    public boolean roundedRectBatch(float[][] bounds, UiColor[] colors, float radius, int vpWidth, int vpHeight) {
        return rects != null && rects.drawBatch(bounds, colors, radius, vpWidth, vpHeight);
    }

    /** Propre à la passe GUI de Blaze3D : décline, la façade dessine un rect normal. */
    @Override
    public boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius,
                                  UiColor color, int vpWidth, int vpHeight) {
        return false;
    }

    // ── Texte ─────────────────────────────────────────────────────────────

    /** Dans un lot ouvert, la chaîne est retenue ; sinon dessinée tout de suite. */
    @Override
    public boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                        int vpWidth, int vpHeight) {
        if (textBatch != null && textBatch.isOpen()) {
            textBatch.add(font, content, x, y, color, scale);
            return true;
        }
        if (textRenderer == null) return false;
        textRenderer.draw(font, content, x, y, color, scale, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean beginTextBatch() {
        if (textBatch == null) return false;
        textBatch.begin();
        return true;
    }

    @Override
    public boolean endTextBatch(int vpWidth, int vpHeight) {
        if (textBatch == null) return false;
        textBatch.end(vpWidth, vpHeight);
        return true;
    }

    // ── Verre dépoli ──────────────────────────────────────────────────────

    @Override
    public boolean glassAvailable() {
        return blur != null && blur.isAvailable();
    }

    @Override
    public boolean beginGlassFrame(int passes, int vpWidth, int vpHeight) {
        return blur != null && blur.beginFrame(passes, vpWidth, vpHeight);
    }

    /**
     * Rayons transmis dans leur ORDRE D'ARRIVÉE, celui que le repli aplat de
     * la façade passe aussi à {@link #roundedRect} : verre et repli ont ainsi
     * exactement la même silhouette. Opacité = alpha de {@code fallback},
     * comme sur Blaze3D.
     */
    @Override
    public boolean glassPanel(float x1, float y1, float x2, float y2,
                              float radiusTopLeft, float radiusTopRight,
                              float radiusBottomLeft, float radiusBottomRight,
                              UiColor tint, float tintStrength, UiColor fallback,
                              int vpWidth, int vpHeight) {
        return blur != null && blur.glassPanel(x1, y1, x2, y2,
            radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            tint, tintStrength, fallback.a, vpWidth, vpHeight);
    }

    @Override
    public boolean blurredPanel(float x1, float y1, float x2, float y2,
                                float radiusTopLeft, float radiusTopRight,
                                float radiusBottomLeft, float radiusBottomRight,
                                int passes, UiColor tint, float tintStrength,
                                int vpWidth, int vpHeight) {
        return blur != null && blur.blurredPanel(x1, y1, x2, y2,
            radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            passes, tint, tintStrength, vpWidth, vpHeight);
    }

    // ── Modes de fusion ───────────────────────────────────────────────────

    @Override
    public boolean blendRect(float x1, float y1, float x2, float y2,
                             float radiusBottomLeft, float radiusBottomRight, float radiusTopLeft, float radiusTopRight,
                             UiColor topColor, UiBlendMode mode, int vpWidth, int vpHeight) {
        return blend != null && blend.draw(x1, y1, x2, y2,
            radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight, topColor, mode, vpWidth, vpHeight);
    }

    // ── Effets et dégradés ────────────────────────────────────────────────

    /**
     * Ombres, contours et lueurs : acceptés mais NON dessinés (2026-09-16),
     * exactement comme sur Blaze3D ({@code Blaze3DBackend.shadow}). Le rendu
     * de référence est celui de 26.1.2, sans ombre ; dessinées ici, elles
     * donnaient à la 1.8.9 des halos sombres et des liserés lumineux absents
     * de 26.1.2 (constaté sur l'écran principal).
     */
    @Override
    public boolean shadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                          UiColor color, int vpWidth, int vpHeight) {
        return true;
    }

    /** Non dessiné — même raison que {@link #shadow}. */
    @Override
    public boolean roundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                     UiColor color, int vpWidth, int vpHeight) {
        return true;
    }

    /**
     * Seul le mode dégradé est dessiné (la façade compose {@code drawGradientRect}
     * dessus). Flou et contour — lueurs de {@code UiShapes.glow} — ne le sont
     * pas, comme sur Blaze3D, qui ne sert pas du tout le FX.
     */
    @Override
    public boolean fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                      UiColor colorA, UiColor colorB, boolean gradient,
                      int vpWidth, int vpHeight) {
        if (!gradient) return true;
        if (primitives == null) return false;
        primitives.fx(x1, y1, x2, y2, radius, blur, borderWidth, colorA, colorB, gradient, vpWidth, vpHeight);
        return true;
    }

    @Override
    public boolean multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                         UiGradientType type,
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

    /**
     * Atlas partagé d'abord ({@link Gl3Icon}) ; une icône qui n'y rentre pas
     * (atlas plein, image trop grande) garde sa propre texture
     * ({@link Gl3PrimitiveRenderer#icon}).
     */
    @Override
    public boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        if (img == null) return true;
        if (icons != null && icons.draw(cacheKey, img, x, y, w, h, alpha, vpWidth, vpHeight)) return true;
        if (primitives == null) return false;
        primitives.icon(cacheKey, img, x, y, w, h, alpha, vpWidth, vpHeight);
        return true;
    }

    // ── Clip ──────────────────────────────────────────────────────────────

    /** Stencil commun aux ères GL ({@link GlRoundedClip}) ; le masque est dessiné par CETTE ère. */
    @Override
    public boolean beginRoundedClip(float x1, float y1, float x2, float y2, float radius,
                                    int vpWidth, int vpHeight) {
        if (roundedClip == null) return false;
        final UiColor opaque = new UiColor(1f, 1f, 1f, 1f);
        roundedClip.begin(() -> roundedRect(x1, y1, x2, y2, radius, opaque, vpWidth, vpHeight));
        return true;
    }

    @Override
    public boolean endRoundedClip() {
        if (roundedClip == null) return false;
        roundedClip.end();
        return true;
    }
}
