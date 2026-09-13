package net.minecraft.client.gui.render.state;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gqg}).
 *
 * <p>DIFFÉRENCE STRUCTURELLE avec la 26.1.2 : pas d'{@code addGuiElement}
 * générique acceptant n'importe quel élément, mais des entrées TYPÉES
 * ({@code addSimpleElement}, {@code addText}, {@code addItem}…). Nos éléments
 * implémentent donc {@link SimpleGuiElementRenderState}.
 */
public class GuiRenderState {

    public void addSimpleElement(SimpleGuiElementRenderState state) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Ouvre une nouvelle strate — pendant de {@code nextStratum()} en 26.1.2. */
    public void createNewRootLayer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    private GuiRenderState() {
    }
}
