package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Dégradé de bord plein écran (vignette) soumis à l'état de GUI de vanilla sur
 * 1.21.11 — pendant exact de {@code VignetteElement} (26.1.2).
 *
 * <p>Un seul quad couvrant tout l'écran ; l'alpha est calculé PAR FRAGMENT à
 * partir de la distance au bord le plus proche (voir
 * {@code GuiElementShaders.VIGNETTE_FRAGMENT}). C'est ce qui remplace l'ancien
 * dessin en OpenGL brut après présentation.
 *
 * <p>Coordonnées en PIXELS GUI, origine en HAUT à gauche, {@code y} vers le
 * BAS — la conversion appartient à {@code VanillaGuiTarget}.
 */
final class VignetteElement1211 implements SimpleGuiElementRenderState {

    private final float x0, y0, x1, y1;
    /** Largeur du dégradé depuis le bord, en pixels GUI. */
    private final float vSize;
    private final int argb;
    private final RenderPipeline pipeline;
    private final ScreenRect bounds;

    VignetteElement1211(float x0, float y0, float x1, float y1,
                        float vSize, int argb, RenderPipeline pipeline) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.vSize = vSize;
        this.argb = argb;
        this.pipeline = pipeline;
        this.bounds = new ScreenRect(
            Math.round(Math.min(x0, x1)), Math.round(Math.min(y0, y1)),
            Math.max(1, Math.round(Math.abs(x1 - x0))),
            Math.max(1, Math.round(Math.abs(y1 - y0))));
    }

    @Override
    public void setupVertices(VertexConsumer c) {
        float halfW = (x1 - x0) * 0.5f;
        float halfH = (y1 - y0) * 0.5f;
        int hw = Math.round(halfW), hh = Math.round(halfH);
        // Borné à 65535 (capacité du créneau) — sans objet en pratique, vSize
        // vaut au plus la moitié de la plus petite dimension d'écran.
        int packedVSize = Math.max(1, Math.min(65535, Math.round(vSize)));

        // Ordre des coins : haut-gauche, bas-gauche, bas-droit, haut-droit — il
        // compte pour le mode QUADS hérité du pipeline de référence.
        vertex(c, x0, y0, -halfW, -halfH, hw, hh, packedVSize);
        vertex(c, x0, y1, -halfW, +halfH, hw, hh, packedVSize);
        vertex(c, x1, y1, +halfW, +halfH, hw, hh, packedVSize);
        vertex(c, x1, y0, +halfW, -halfH, hw, hh, packedVSize);
    }

    /**
     * TOUS les attributs déclarés par le format doivent être écrits pour chaque
     * sommet — y compris le second entier de {@code light}, inutilisé ici.
     * Sinon la construction du maillage échoue et le client plante dans la
     * soumission de la GUI.
     */
    private void vertex(VertexConsumer c, float px, float py,
                        float localX, float localY, int hw, int hh, int packedVSize) {
        c.vertex(px, py, 0f)
         .color(argb)
         .texture(localX, localY)
         .overlay(hw, hh)
         .light(packedVSize, 0);
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }

    @Override
    public ScreenRect scissorArea() {
        return null;
    }

    @Override
    public ScreenRect bounds() {
        return bounds;
    }
}
