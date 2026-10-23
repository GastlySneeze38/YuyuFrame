package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_2.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_2.vanillagui.pipeline.Blaze3DGuiIcon;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Un modèle 3D déjà projeté ({@code draw/mesh}), soumis à l'état de GUI de
 * vanilla : une suite de quads texturés, colorés par sommet, dans l'ordre où
 * les dessiner.
 *
 * <p>Même pipeline et même atlas que {@link IconElement} — une icône dont les
 * quatre coins sont libres, répétée. Un seul élément pour tout le modèle :
 * l'ordre de ses quads, décidé par le projecteur, est l'ordre de dessin.
 *
 * <p>Coordonnées en PIXELS GUI, Y vers le bas (converties par
 * {@code VanillaGuiTarget}).
 */
public final class MeshElement implements GuiElementRenderState {

    private final float[] xy;
    private final float[] uv;
    private final int[] argb;
    private final int quads;
    private final TextureSetup textureSetup;
    private final ScreenRectangle bounds;

    /**
     * @param atlasUv {@code {u0, v0, u1, v1}} de l'image dans l'atlas : les
     *                {@code uv} du modèle (0 à 1 dans l'image) y sont ramenés
     */
    public MeshElement(float[] xy, float[] uv, int[] argb, int quads, float[] atlasUv, TextureSetup textureSetup) {
        this.xy = xy;
        this.quads = quads;
        this.argb = argb;
        this.textureSetup = textureSetup;
        this.uv = new float[quads * 8];
        float u0 = atlasUv[0], v0 = atlasUv[1], du = atlasUv[2] - atlasUv[0], dv = atlasUv[3] - atlasUv[1];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < quads * 4; i++) {
            this.uv[i * 2] = u0 + uv[i * 2] * du;
            this.uv[i * 2 + 1] = v0 + uv[i * 2 + 1] * dv;
            minX = Math.min(minX, xy[i * 2]); maxX = Math.max(maxX, xy[i * 2]);
            minY = Math.min(minY, xy[i * 2 + 1]); maxY = Math.max(maxY, xy[i * 2 + 1]);
        }
        this.bounds = new ScreenRectangle(Math.round(minX), Math.round(minY),
            Math.max(1, Math.round(maxX - minX)), Math.max(1, Math.round(maxY - minY)));
    }

    @Override
    public void buildVertices(VertexConsumer c) {
        for (int i = 0; i < quads * 4; i++) {
            c.addVertex(xy[i * 2], xy[i * 2 + 1], 0f).setColor(argb[i]).setUv(uv[i * 2], uv[i * 2 + 1]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return (RenderPipeline) Blaze3DGuiIcon.pipeline();
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
        return bounds;
    }
}
