package net.minecraft.client.gui.render.state;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gqe}) — interface en jeu,
 * vérifiée par {@code javap} ({@code public interface gqe extends gqi}).
 *
 * <p>Pendant de {@code GuiElementRenderState} en 26.1.2, à deux différences
 * près : la méthode de remplissage s'appelle {@code setupVertices} (et non
 * {@code buildVertices}), et c'est CETTE interface (pas sa parente) qu'il faut
 * implémenter — {@code GuiRenderState.addSimpleElement} n'accepte qu'elle.
 */
public interface SimpleGuiElementRenderState extends GuiElementRenderState {

    void setupVertices(VertexConsumer vertices);

    RenderPipeline pipeline();

    TextureSetup textureSetup();

    ScreenRect scissorArea();
}
