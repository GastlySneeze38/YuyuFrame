package net.minecraft.client.renderer.state.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Stub compile-only (26.1+) — super-interface de
 * {@link GuiElementRenderState}. Vanilla s'en sert pour placer l'élément dans
 * l'arbre de strates ({@code GuiRenderState.findAppropriateNode}), d'où
 * l'obligation de fournir des bornes cohérentes.
 */
public interface ScreenArea {
    ScreenRectangle bounds();
}
