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

    /**
     * 26.3 — un rectangle de couleur, dessiné par la même voie que le texte
     * des étiquettes (vérifié javap sur le jar 26.3 : coins {@code x0, y0, x1, y1},
     * couleur ARGB, mode d'affichage, lumière empaquetée).
     */
    void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                              net.minecraft.client.gui.Font.DisplayMode displayMode, int lightCoords);
}
