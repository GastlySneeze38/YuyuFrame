package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.pipeline.Blaze3DGuiVignette;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Dégradé de bord plein écran (vignette) soumis à l'état de GUI de vanilla —
 * pendant de {@link RoundedRectElement} pour l'effet plein écran de
 * {@code LowHealthTintModule}.
 *
 * <p>Un seul quad couvrant tout l'écran ; l'alpha est calculé PAR FRAGMENT à
 * partir de la distance au bord le plus proche (voir
 * {@link Blaze3DGuiVignette}). C'est ce qui remplace l'ancien dessin en
 * OpenGL brut après présentation, source des corruptions d'état constatées
 * sur 26.1.2.
 *
 * <p>Coordonnées en PIXELS GUI, origine en haut à gauche, {@code y} vers le
 * bas — convention vanilla ; la conversion appartient à
 * {@link VanillaGuiTarget}.
 */
public final class VignetteElement implements GuiElementRenderState {

    private final float x0, y0, x1, y1;
    /** Largeur du dégradé depuis le bord, en pixels GUI. */
    private final float vSize;
    private final int argb;
    private final ScreenRectangle bounds;

    public VignetteElement(float x0, float y0, float x1, float y1, float vSize, int argb) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.vSize = vSize;
        this.argb = argb;
        this.bounds = new ScreenRectangle(
            Math.round(Math.min(x0, x1)), Math.round(Math.min(y0, y1)),
            Math.max(1, Math.round(Math.abs(x1 - x0))),
            Math.max(1, Math.round(Math.abs(y1 - y0))));
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float halfW = (x1 - x0) * 0.5f;
        float halfH = (y1 - y0) * 0.5f;
        int hw = Math.round(halfW), hh = Math.round(halfH);
        // Borné à 65535 (capacité du créneau UV2) — sans objet en pratique,
        // vSize vaut au plus la moitié de la plus petite dimension d'écran.
        int packedVSize = Math.max(1, Math.min(65535, Math.round(vSize)));

        // Même ordre de coins que ColoredRectangleRenderState (haut-gauche,
        // bas-gauche, bas-droit, haut-droit) — il compte pour le mode QUADS
        // hérité du pipeline de référence.
        vertex(consumer, x0, y0, -halfW, -halfH, hw, hh, packedVSize);
        vertex(consumer, x0, y1, -halfW, +halfH, hw, hh, packedVSize);
        vertex(consumer, x1, y1, +halfW, +halfH, hw, hh, packedVSize);
        vertex(consumer, x1, y0, +halfW, -halfH, hw, hh, packedVSize);
    }

    /**
     * TOUS les attributs déclarés par le format doivent être écrits pour
     * chaque sommet, sinon {@code BufferBuilder.endLastVertex} lève
     * ({@code Missing elements in vertex}) et le client plante dans
     * {@code GuiRenderer.prepare} — y compris {@code UV2.y}, inutilisé ici.
     */
    private void vertex(VertexConsumer c, float px, float py,
                        float localX, float localY, int hw, int hh, int packedVSize) {
        c.addVertex(px, py, 0f)
         .setColor(argb)
         .setUv(localX, localY)
         .setUv1(hw, hh)
         .setUv2(packedVSize, 0);
    }

    @Override
    public RenderPipeline pipeline() {
        return (RenderPipeline) Blaze3DGuiVignette.pipeline();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
