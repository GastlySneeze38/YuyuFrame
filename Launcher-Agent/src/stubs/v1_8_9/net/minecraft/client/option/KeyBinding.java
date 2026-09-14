package net.minecraft.client.option;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code avb}).
 *
 * <p>{@code getCode()} rend un code de touche LWJGL 2 (scancode DirectInput),
 * NÉGATIF pour un bouton de souris ({@code bouton - 100}) — pas un code GLFW.
 */
public class KeyBinding {

    private KeyBinding() {
    }

    public boolean isPressed() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getCode() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
