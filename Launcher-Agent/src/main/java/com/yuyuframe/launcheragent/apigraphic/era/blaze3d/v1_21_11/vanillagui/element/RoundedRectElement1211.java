package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Rect arrondi soumis à l'état de GUI de vanilla sur 1.21.11 — pendant exact de
 * {@code RoundedRectElement} (26.1.2), même géométrie et même empaquetage.
 *
 * <p>Trois différences, toutes de NOMS (voir les stubs) : l'interface est
 * {@link SimpleGuiElementRenderState} (seule acceptée par {@code
 * GuiRenderState.addSimpleElement}), la méthode de remplissage s'appelle
 * {@code setupVertices}, et le {@code VertexConsumer} dit {@code vertex/color/
 * texture/overlay/light} là où la 26.1.2 dit {@code addVertex/setColor/setUv/
 * setUv1/setUv2}.
 *
 * <p>Coordonnées en PIXELS GUI, origine en HAUT à gauche, {@code y} vers le
 * BAS — la conversion appartient à {@code VanillaGuiTarget}.
 */
@YarnNamed
public final class RoundedRectElement1211 implements SimpleGuiElementRenderState {

    private final float x0, y0, x1, y1;
    /** Rayons en pixels GUI, repère Y VERS LE BAS : haut-gauche, haut-droit, bas-gauche, bas-droit. */
    private final float rTopLeft, rTopRight, rBottomLeft, rBottomRight;
    private final int argb;
    private final RenderPipeline pipeline;
    private final ScreenRect bounds;

    public RoundedRectElement1211(float x0, float y0, float x1, float y1,
                           float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                           int argb, RenderPipeline pipeline) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.rTopLeft = rTopLeft; this.rTopRight = rTopRight;
        this.rBottomLeft = rBottomLeft; this.rBottomRight = rBottomRight;
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

        // Deux rayons par entier court, 8 bits chacun — voir GuiElementShaders.
        int packedTop = (clampRadius(rTopLeft, halfW, halfH) << 8) | clampRadius(rTopRight, halfW, halfH);
        int packedBottom = (clampRadius(rBottomLeft, halfW, halfH) << 8) | clampRadius(rBottomRight, halfW, halfH);

        // Les quatre coins dans l'ordre haut-gauche, bas-gauche, bas-droit,
        // haut-droit — l'ordre compte pour le mode QUADS du pipeline copié.
        vertex(c, x0, y0, -halfW, -halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x0, y1, -halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x1, y1, +halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x1, y0, +halfW, -halfH, hw, hh, packedTop, packedBottom);
    }

    /**
     * Borne un rayon à la demi-dimension (au-delà, les coins se recouvrent) ET
     * à 255, capacité d'un octet de l'empaquetage. Sans contrainte en pratique :
     * le réglage d'interface plafonne à 16 pixels GUI.
     */
    private static int clampRadius(float radius, float halfW, float halfH) {
        float bounded = Math.max(0f, Math.min(radius, Math.min(halfW, halfH)));
        return Math.min(255, Math.round(bounded));
    }

    /**
     * TOUS les attributs déclarés par le format doivent être écrits pour chaque
     * sommet, sinon la construction du maillage échoue et le client crashe dans
     * la soumission de la GUI.
     */
    private void vertex(VertexConsumer c, float px, float py,
                        float localX, float localY, int hw, int hh, int packedTop, int packedBottom) {
        c.vertex(px, py, 0f)
         .color(argb)
         .texture(localX, localY)
         .overlay(hw, hh)
         .light(packedTop, packedBottom);
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }

    /** Pas de découpe — le HUD n'en a pas besoin. */
    @Override
    public ScreenRect scissorArea() {
        return null;
    }

    @Override
    public ScreenRect bounds() {
        return bounds;
    }
}
