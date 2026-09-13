package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Icône RGBA soumise à l'état de GUI de vanilla sur 1.21.11 — pendant exact de
 * {@code IconElement} (26.1.2).
 *
 * <p>Les UV pointent un sous-rect de l'ATLAS partagé, pas une texture dédiée :
 * toutes les icônes portent donc le même {@code TextureSetup} et se regroupent
 * en un seul maillage quand elles se suivent.
 *
 * <p>Coordonnées en PIXELS GUI, origine en HAUT à gauche, {@code y} vers le
 * BAS — la conversion appartient à {@code VanillaGuiTarget}.
 */
@YarnNamed
public final class IconElement1211 implements SimpleGuiElementRenderState {

    private final float x0, y0, x1, y1;
    private final float u0, v0, u1, v1;
    private final int argb;
    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final ScreenRect bounds;

    public IconElement1211(float x0, float y0, float x1, float y1,
                    float[] uv, int argb, RenderPipeline pipeline, TextureSetup textureSetup) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.u0 = uv[0]; this.v0 = uv[1]; this.u1 = uv[2]; this.v1 = uv[3];
        this.argb = argb;
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;
        this.bounds = new ScreenRect(
            Math.round(Math.min(x0, x1)), Math.round(Math.min(y0, y1)),
            Math.max(1, Math.round(Math.abs(x1 - x0))),
            Math.max(1, Math.round(Math.abs(y1 - y0))));
    }

    /**
     * {@code v0} est le HAUT de l'image dans l'atlas et {@code y0} le haut à
     * l'écran (Y vers le bas ici) : l'appariement est DIRECT, sans inversion —
     * contrairement au chemin de la file Blaze3D, qui travaille en Y vers le
     * haut et doit croiser les deux. Se tromper ici retourne l'image
     * verticalement, sans le moindre message pour prévenir.
     */
    @Override
    public void setupVertices(VertexConsumer c) {
        c.vertex(x0, y0, 0f).color(argb).texture(u0, v0);
        c.vertex(x0, y1, 0f).color(argb).texture(u0, v1);
        c.vertex(x1, y1, 0f).color(argb).texture(u1, v1);
        c.vertex(x1, y0, 0f).color(argb).texture(u1, v0);
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
