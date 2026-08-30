package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiRoundedRect;
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
    private final float radius;
    private final int argb;

    public RoundedRectElement(float x0, float y0, float x1, float y1, float radius, int argb) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.radius = radius;
        this.argb = argb;
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float halfW = (x1 - x0) * 0.5f;
        float halfH = (y1 - y0) * 0.5f;
        // Rayon borné à la demi-dimension : au-delà, le SDF produirait des
        // coins qui se recouvrent et une forme incohérente.
        int r = Math.round(Math.max(0f, Math.min(radius, Math.min(halfW, halfH))));
        int hw = Math.round(halfW), hh = Math.round(halfH);

        // Les quatre coins, dans le même ordre que ColoredRectangleRenderState
        // (haut-gauche, bas-gauche, bas-droit, haut-droit) — l'ordre compte
        // pour le mode QUADS hérité du pipeline de référence.
        vertex(consumer, x0, y0, -halfW, -halfH, hw, hh, r);
        vertex(consumer, x0, y1, -halfW, +halfH, hw, hh, r);
        vertex(consumer, x1, y1, +halfW, +halfH, hw, hh, r);
        vertex(consumer, x1, y0, +halfW, -halfH, hw, hh, r);
    }

    /**
     * TOUS les attributs déclarés par le format doivent être écrits pour
     * chaque sommet : {@code BufferBuilder.endLastVertex} lève sinon
     * ({@code Missing elements in vertex}), et le client crashe dans
     * {@code GuiRenderer.prepare}.
     */
    private void vertex(VertexConsumer c, float px, float py,
                        float localX, float localY, int hw, int hh, int r) {
        c.addVertex(px, py, 0f)
         .setColor(argb)
         .setUv(localX, localY)
         .setUv1(hw, hh)
         .setUv2(r, 0);
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
