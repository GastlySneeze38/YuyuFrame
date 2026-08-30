package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiGlass;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Panneau de verre dépoli soumis à l'état de GUI de vanilla.
 *
 * <p>Géométrie IDENTIQUE à {@link RoundedRectElement} — mêmes attributs, même
 * empaquetage des rayons, même masque SDF. Seul le fragment diffère : il
 * échantillonne la texture floutée au lieu de peindre un aplat (voir
 * {@link Blaze3DGuiGlass}).
 *
 * <p>Deux classes plutôt qu'un drapeau : le pipeline et le
 * {@code TextureSetup} diffèrent, et ce sont EUX qui déterminent le
 * regroupement en maillages côté vanilla. Les mélanger dans un seul type
 * n'aurait rien économisé et aurait brouillé la lecture.
 */
public final class GlassPanelElement implements GuiElementRenderState {

    private final float x0, y0, x1, y1;
    private final float rTopLeft, rTopRight, rBottomLeft, rBottomRight;
    private final int argb;
    private final TextureSetup textureSetup;

    public GlassPanelElement(float x0, float y0, float x1, float y1,
                             float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                             int argb, TextureSetup textureSetup) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.rTopLeft = rTopLeft; this.rTopRight = rTopRight;
        this.rBottomLeft = rBottomLeft; this.rBottomRight = rBottomRight;
        this.argb = argb;
        this.textureSetup = textureSetup;
    }

    @Override
    public void buildVertices(VertexConsumer c) {
        float halfW = (x1 - x0) * 0.5f;
        float halfH = (y1 - y0) * 0.5f;
        int hw = Math.round(halfW), hh = Math.round(halfH);
        int packedTop = (clampRadius(rTopLeft, halfW, halfH) << 8) | clampRadius(rTopRight, halfW, halfH);
        int packedBottom = (clampRadius(rBottomLeft, halfW, halfH) << 8) | clampRadius(rBottomRight, halfW, halfH);

        vertex(c, x0, y0, -halfW, -halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x0, y1, -halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x1, y1, +halfW, +halfH, hw, hh, packedTop, packedBottom);
        vertex(c, x1, y0, +halfW, -halfH, hw, hh, packedTop, packedBottom);
    }

    private static int clampRadius(float radius, float halfW, float halfH) {
        float bounded = Math.max(0f, Math.min(radius, Math.min(halfW, halfH)));
        return Math.min(255, Math.round(bounded));
    }

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
        return (RenderPipeline) Blaze3DGuiGlass.pipeline();
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

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
