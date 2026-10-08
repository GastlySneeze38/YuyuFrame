package net.minecraft.client.render.command;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code hop}) — la file de commandes de
 * dessin du monde. C'est ICI que {@code submitCustom} est déclarée
 * ({@link OrderedRenderCommandQueue} en hérite) : l'appeler sur ce type, sans
 * quoi le traducteur de noms ne la trouve pas.
 */
public interface RenderCommandQueue {

    void submitCustom(MatrixStack matrices, RenderLayer layer, OrderedRenderCommandQueue.Custom renderer);
}
