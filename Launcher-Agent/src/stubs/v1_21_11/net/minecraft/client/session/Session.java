package net.minecraft.client.session;

/**
 * Stub compile-only 1.21.11, nom Yarn — poignée OPAQUE : la session du joueur.
 *
 * <p>Aucun membre : ce type ne sert qu'à donner au bytecode le bon descripteur
 * (un champ ou un retour de méthode se résout par son TYPE autant que par son
 * nom). Les liaisons le repassent tel quel en {@code Object} — voir
 * {@code AccessorBindings1211} et les règles de l'unité dans
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 */
public class Session {

    private Session() {
    }

    /** Pendant de {@code User.getName()} en 26.1.2 — sert {@code CLIENT_USERNAME}. */
    public String getUsername() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
