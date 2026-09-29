package net.minecraft.client.renderer.state.gui;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Stub compile-only (26.1+) — <b>le point d'extension qui rend la refonte du
 * rendu possible</b> : une interface PUBLIQUE que nous pouvons implémenter
 * pour soumettre nos propres dessins à l'état de GUI de vanilla, avec notre
 * pipeline ET nos attributs de sommet.
 *
 * <p>Hérite de {@link ScreenArea} (vérifié sur le jar réel), donc cinq
 * méthodes à fournir au total, pas quatre.
 */
public interface GuiElementRenderState extends ScreenArea {

    void buildVertices(VertexConsumer consumer);

    RenderPipeline pipeline();

    TextureSetup textureSetup();

    ScreenRectangle scissorArea();
}
