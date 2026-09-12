package net.minecraft.client.util;

/**
 * Stub compile-only 1.21.11, nom Yarn — poignée OPAQUE : la fenêtre.
 *
 * <p>Aucun membre : ce type ne sert qu'à donner au bytecode le bon descripteur
 * (un champ ou un retour de méthode se résout par son TYPE autant que par son
 * nom). Les liaisons le repassent tel quel en {@code Object} — voir
 * {@code AccessorBindings1211} et les règles de l'unité dans
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 */
public class Window {

    private Window() {
    }

    /** Largeur en pixels GUI (échelle vanilla appliquée) — {@code getGuiScaledWidth()} en 26.1.2. */
    public int getScaledWidth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Hauteur en pixels GUI — {@code getGuiScaledHeight()} en 26.1.2. */
    public int getScaledHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
