package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Panneau de verre dépoli soumis à l'état de GUI de vanilla sur 1.21.11 —
 * pendant exact de {@code GlassPanelElement} (26.1.2).
 *
 * <p>Géométrie IDENTIQUE à {@link RoundedRectElement1211} : mêmes attributs,
 * même empaquetage des rayons, même masque SDF. Seul le fragment diffère — il
 * échantillonne la texture floutée au lieu de peindre un aplat.
 *
 * <p>Deux classes plutôt qu'un drapeau : le pipeline ET le
 * {@code TextureSetup} diffèrent, et ce sont EUX qui décident du regroupement
 * en maillages côté vanilla.
 */
@YarnNamed
public final class GlassPanelElement1211 implements SimpleGuiElementRenderState {

    private final float x0, y0, x1, y1;
    private final float rTopLeft, rTopRight, rBottomLeft, rBottomRight;
    private final int argb;
    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final ScreenRect bounds;

    public GlassPanelElement1211(float x0, float y0, float x1, float y1,
                          float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                          int argb, RenderPipeline pipeline, TextureSetup textureSetup) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.rTopLeft = rTopLeft; this.rTopRight = rTopRight;
        this.rBottomLeft = rBottomLeft; this.rBottomRight = rBottomRight;
        this.argb = argb;
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;
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
        return textureSetup;
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
