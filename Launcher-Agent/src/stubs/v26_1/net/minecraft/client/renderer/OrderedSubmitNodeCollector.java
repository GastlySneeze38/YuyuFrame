package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Stub compile-only (26.1+) — la file de commandes de dessin du monde. C'est
 * ICI que {@code submitCustomGeometry} est déclarée en jeu (vérifié javap sur
 * le jar 26.1.2), pas sur {@link SubmitNodeCollector} qui en hérite.
 */
public interface OrderedSubmitNodeCollector {

    void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                              SubmitNodeCollector.CustomGeometryRenderer renderer);
}
