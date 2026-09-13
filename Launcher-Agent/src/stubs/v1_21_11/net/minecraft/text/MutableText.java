package net.minecraft.text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code yw}) — un texte MODIFIABLE,
 * pendant de {@code MutableComponent} en 26.1.2.
 *
 * <p>C'est une CLASSE en jeu et une sous-classe de {@link Text} : les deux
 * comptent, puisque {@code addMessage} prend un {@code Text} et que
 * {@code copy()} rend un {@code MutableText}.
 */
public class MutableText implements Text {

    protected MutableText() {
    }

    @Override
    public String getString() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public MutableText copy() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Ajoute un texte à la suite — {@code append} des deux côtés. */
    public MutableText append(Text sibling) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Teinte ce texte — {@code withColor} des deux côtés. */
    public MutableText withColor(int rgb) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
