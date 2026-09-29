package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.pipeline.Blaze3DGuiRoundedRect;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Un rect arrondi soumis à l'état de GUI de vanilla — <b>notre première
 * implémentation de {@link GuiElementRenderState}</b> (étape 1c de la refonte
 * du rendu, 2026-08-30).
 *
 * <h2>Pourquoi implémenter l'interface plutôt qu'utiliser {@code fill}</h2>
 *
 * {@code GuiGraphicsExtractor.fill(pipeline, …)} accepte bien un pipeline à
 * nous, mais c'est {@code ColoredRectangleRenderState} qui remplit les
 * sommets, et lui n'écrit que {@code Position + Color}. Impossible d'y faire
 * passer les paramètres d'un SDF. En implémentant l'interface, c'est NOTRE
 * {@link #buildVertices} qui écrit — donc autant d'attributs qu'en déclare
 * notre format (voir {@link Blaze3DGuiRoundedRect}).
 *
 * <p>L'interface hérite de {@code ScreenArea}, d'où {@link #bounds()} en plus
 * des quatre méthodes évidentes : vanilla s'en sert pour placer l'élément dans
 * son arbre de strates. Des bornes fausses ne feraient pas planter, mais
 * fausseraient le classement en profondeur.
 *
 * <h2>Coordonnées</h2>
 *
 * Tout est en PIXELS GUI, origine en HAUT à gauche, {@code y} vers le BAS —
 * convention vanilla, l'inverse de notre moteur. La conversion appartient à
 * l'appelant.
 *
 * <p>{@code Position.z = 0} : l'état de GUI est en 2D, la profondeur vient de
 * l'ordre d'insertion, jamais du z.
 */
public final class RoundedRectElement implements GuiElementRenderState {

    private final float x0, y0, x1, y1;
    /** Rayons en pixels GUI, repère Y VERS LE BAS : haut-gauche, haut-droit, bas-gauche, bas-droit. */
    private final float rTopLeft, rTopRight, rBottomLeft, rBottomRight;
    private final int argb;

    /** Rayon uniforme aux quatre coins. */
    public RoundedRectElement(float x0, float y0, float x1, float y1, float radius, int argb) {
        this(x0, y0, x1, y1, radius, radius, radius, radius, argb);
    }

    /**
     * Rayon PAR COIN — c'est ce qui permet à un panneau collé à un bord
     * d'écran de garder ses coins carrés de ce côté (voir
     * {@code HudPanelRenderer.edgeAwareRadii}, qui décide lesquels).
     */
    public RoundedRectElement(float x0, float y0, float x1, float y1,
                              float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                              int argb) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.rTopLeft = rTopLeft; this.rTopRight = rTopRight;
        this.rBottomLeft = rBottomLeft; this.rBottomRight = rBottomRight;
        this.argb = argb;
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float halfW = (x1 - x0) * 0.5f;
        float halfH = (y1 - y0) * 0.5f;
        int hw = Math.round(halfW), hh = Math.round(halfH);

        // Deux rayons par entier court, 8 bits chacun — voir Blaze3DGuiRoundedRect.
        int packedTop = (clampRadius(rTopLeft, halfW, halfH) << 8) | clampRadius(rTopRight, halfW, halfH);
        int packedBottom = (clampRadius(rBottomLeft, halfW, halfH) << 8) | clampRadius(rBottomRight, halfW, halfH);

        // Les quatre coins, dans le même ordre que ColoredRectangleRenderState
        // (haut-gauche, bas-gauche, bas-droit, haut-droit) — l'ordre compte
        // pour le mode QUADS hérité du pipeline de référence.
        vertex(consumer, x0, y0, -halfW, -halfH, hw, hh, packedTop, packedBottom);
        vertex(consumer, x0, y1, -halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(consumer, x1, y1, +halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(consumer, x1, y0, +halfW, -halfH, hw, hh, packedTop, packedBottom);
    }

    /**
     * Borne un rayon à la demi-dimension (au-delà, les coins se recouvrent et
     * la forme devient incohérente) ET à 255, la capacité d'un octet de
     * l'empaquetage. Aucune contrainte en pratique : le réglage d'interface
     * plafonne à 16 pixels GUI.
     */
    private static int clampRadius(float radius, float halfW, float halfH) {
        float bounded = Math.max(0f, Math.min(radius, Math.min(halfW, halfH)));
        return Math.min(255, Math.round(bounded));
    }

    /**
     * TOUS les attributs déclarés par le format doivent être écrits pour
     * chaque sommet : {@code BufferBuilder.endLastVertex} lève sinon
     * ({@code Missing elements in vertex}), et le client crashe dans
     * {@code GuiRenderer.prepare}.
     */
    private void vertex(VertexConsumer c, float px, float py,
                        float localX, float localY, int hw, int hh, int packedTop, int packedBottom) {
        c.addVertex(px, py, 0f)
         .setColor(argb)
         .setUv(localX, localY)
         .setUv1(hw, hh)
         .setUv2(packedTop, packedBottom);
    }

    @Override
    public RenderPipeline pipeline() {
        return (RenderPipeline) Blaze3DGuiRoundedRect.pipeline();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    /** Pas de découpe — le HUD n'en a pas besoin pour l'instant. */
    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        int bx = Math.round(Math.min(x0, x1));
        int by = Math.round(Math.min(y0, y1));
        int bw = Math.round(Math.abs(x1 - x0));
        int bh = Math.round(Math.abs(y1 - y0));
        return new ScreenRectangle(bx, by, bw, bh);
    }
}
