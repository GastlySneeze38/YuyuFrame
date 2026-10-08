package net.minecraft.text;

import java.util.Optional;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code yn}) — super-interface de
 * {@link Text}. {@code visit} est déclarée ICI : l'appeler sur ce type.
 */
public interface StringVisitable {

    /** Parcourt le texte dans l'ordre d'affichage ; s'arrête au premier résultat non vide. */
    default <T> Optional<T> visit(StyledVisitor<T> visitor, Style style) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code yn$b} — reçoit chaque morceau avec son style déjà résolu. {@code accept} n'est pas obfusquée. */
    interface StyledVisitor<T> {
        Optional<T> accept(Style style, String text);
    }
}
