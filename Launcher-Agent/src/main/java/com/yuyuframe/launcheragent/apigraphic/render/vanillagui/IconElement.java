package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DGuiIcon;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Une icône RGBA soumise à l'état de GUI de vanilla — pendant de
 * {@link RoundedRectElement} pour les images.
 *
 * <p>Les UV pointent un sous-rect de l'ATLAS partagé (voir
 * {@link Blaze3DGuiIcon}), pas une texture dédiée : toutes les icônes
 * partagent donc le même {@code TextureSetup} et se regroupent en un seul
 * maillage quand elles se suivent.
 *
 * <p>Coordonnées en PIXELS GUI, origine en haut à gauche, {@code y} vers le
 * bas — convention vanilla ; la conversion appartient à
 * {@link VanillaGuiTarget}.
 */
public final class IconElement implements GuiElementRenderState {

    private final float x0, y0, x1, y1;
    private final float u0, v0, u1, v1;
    private final int argb;
    private final TextureSetup textureSetup;
    private final ScreenRectangle bounds;

    public IconElement(float x0, float y0, float x1, float y1,
                       float[] uv, int argb, TextureSetup textureSetup) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.u0 = uv[0]; this.v0 = uv[1]; this.u1 = uv[2]; this.v1 = uv[3];
        this.argb = argb;
        this.textureSetup = textureSetup;
        this.bounds = new ScreenRectangle(
            Math.round(Math.min(x0, x1)), Math.round(Math.min(y0, y1)),
            Math.max(1, Math.round(Math.abs(x1 - x0))),
            Math.max(1, Math.round(Math.abs(y1 - y0))));
    }

    /**
     * {@code v0} est le HAUT de l'image dans l'atlas et {@code y0} le haut à
     * l'écran (Y vers le bas ici) : l'appariement est direct, sans inversion
     * — contrairement au chemin Blaze3D, qui travaille en Y vers le haut et
     * doit croiser les deux (voir {@code Blaze3DRect.drawIcon}). Se tromper
     * ici retourne l'image verticalement, pas de crash pour prévenir.
     */
    @Override
    public void buildVertices(VertexConsumer c) {
        c.addVertex(x0, y0, 0f).setColor(argb).setUv(u0, v0);
        c.addVertex(x0, y1, 0f).setColor(argb).setUv(u0, v1);
        c.addVertex(x1, y1, 0f).setColor(argb).setUv(u1, v1);
        c.addVertex(x1, y0, 0f).setColor(argb).setUv(u1, v0);
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
