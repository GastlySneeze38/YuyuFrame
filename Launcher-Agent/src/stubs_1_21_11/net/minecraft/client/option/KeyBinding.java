package net.minecraft.client.option;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gfh}).
 *
 * <p>{@code isPressed()} est public ; {@code boundKey} reste privé et n'est
 * donc pas exposé ici — le point d'accès {@code KEYBIND_KEY} reste NON LIÉ sur
 * cette version (le registre sert alors sa valeur de repli, voir
 * {@code AccessorBindings1211}).
 */
public class KeyBinding {

    private KeyBinding() {
    }

    public boolean isPressed() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
