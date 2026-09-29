package net.minecraft.client.renderer.state.gui;

/**
 * Stub compile-only (26.1+) — l'état de GUI que vanilla accumule pendant
 * {@code GameRenderer.render} puis soumet en une fois via
 * {@code GuiRenderer.render}. Ordre du PEINTRE : le dernier ajouté est
 * dessiné au-dessus, ce qui fait du z-order une propriété du MOMENT
 * d'insertion.
 *
 * <p>Atteint via {@code GuiGraphicsExtractorAccessor261} (le champ est privé).
 */
public final class GuiRenderState {
    private GuiRenderState() {}

    public void addGuiElement(GuiElementRenderState element) {}

    /** Ouvre une strate : tout ce qui suit passe au-dessus de ce qui précède. */
    public void nextStratum() {}
}
