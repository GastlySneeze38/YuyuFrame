package net.minecraft.network.chat;

import java.util.Optional;

/**
 * Stub compile-only (26.1+) — super-interface de {@link Component} en jeu.
 * Déclarée pour que {@code Font.width(FormattedText)} reçoive un
 * {@code Component} avec le BON descripteur d'appel, et pour parcourir un
 * texte morceau par morceau avec son style. Signatures vérifiées par javap
 * sur le jar client 26.1.2.
 */
public interface FormattedText {

    /** Parcourt le texte dans l'ordre d'affichage ; s'arrête au premier résultat non vide. */
    default <T> Optional<T> visit(StyledContentConsumer<T> consumer, Style style) { return Optional.empty(); }

    /** {@code FormattedText$StyledContentConsumer} — reçoit chaque morceau avec son style déjà résolu. */
    interface StyledContentConsumer<T> {
        Optional<T> accept(Style style, String text);
    }
}
