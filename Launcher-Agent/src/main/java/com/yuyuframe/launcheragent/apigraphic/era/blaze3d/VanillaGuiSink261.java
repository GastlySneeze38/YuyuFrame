package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

import java.awt.image.BufferedImage;

/**
 * {@link VanillaGuiSink} de la 26.1.2 — délègue à {@link VanillaGuiLayer},
 * inchangée.
 *
 * <p>Aucune ligne de rendu n'a été réécrite en introduisant l'interface : ce
 * fichier ne fait que donner une FORME commune au chemin déjà validé en jeu,
 * pour que la 1.21.11 puisse en fournir un pendant. Même précaution que lors de
 * l'introduction de {@code Blaze3DGpu}.
 *
 * <p>Sur une autre version, {@link #accepts} répond {@code false} : le contexte
 * du hook n'y est pas un {@code GuiGraphicsExtractor}, {@link VanillaGuiLayer}
 * le détecte et le journalise une fois.
 */
final class VanillaGuiSink261 implements VanillaGuiSink {

    @Override
    public String id() {
        return "26.1.2";
    }

    @Override
    public boolean accepts(Object hookContext) {
        return VanillaGuiLayer.isAvailable(hookContext);
    }

    @Override
    public int guiWidth(Object hookContext) {
        return VanillaGuiLayer.guiWidth(hookContext);
    }

    @Override
    public boolean ensureCompiled() {
        // `|` et non `||` : les DEUX doivent être tentés, sinon un échec du
        // premier empêcherait le second de se compiler pour toujours.
        boolean rect = Blaze3DGuiRoundedRect.ensureCompiled();
        boolean text = Blaze3DGuiText.ensureCompiled();
        return rect && text;
    }

    @Override
    public boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                               float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                               UiColor color) {
        return VanillaGuiLayer.roundedRect(hookContext, x0, y0, x1, y1,
            rTopLeft, rTopRight, rBottomLeft, rBottomRight, color);
    }

    @Override
    public boolean text(Object hookContext, UiFont font, String content,
                        float x, float baselineY, float scale, UiColor color) {
        return VanillaGuiLayer.text(hookContext, font, content, x, baselineY, scale, color);
    }

    @Override
    public boolean icon(Object hookContext, String cacheKey, BufferedImage img,
                        float x0, float y0, float x1, float y1, float alpha) {
        return VanillaGuiLayer.icon(hookContext, cacheKey, img, x0, y0, x1, y1, alpha);
    }

    @Override
    public boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                            float vSize, UiColor color) {
        return VanillaGuiLayer.vignette(hookContext, x0, y0, x1, y1, vSize, color);
    }

    @Override
    public boolean supportsGlassPanel() {
        return true;
    }

    @Override
    public boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                              float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                              UiColor tint, UiColor background) {
        return VanillaGuiLayer.glassPanel(hookContext, x0, y0, x1, y1,
            rTopLeft, rTopRight, rBottomLeft, rBottomRight, tint, background);
    }

    @Override
    public void flushItemIcons(Object hookContext) {
        VanillaGuiLayer.flushItemIcons(hookContext);
    }
}
