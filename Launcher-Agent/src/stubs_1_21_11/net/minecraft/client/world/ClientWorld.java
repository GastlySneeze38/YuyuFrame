package net.minecraft.client.world;

/**
 * Stub compile-only 1.21.11, nom Yarn — poignée OPAQUE : le monde côté client.
 *
 * <p>Aucun membre PROPRE : ce type ne sert qu'à donner au bytecode le bon
 * descripteur (un champ ou un retour de méthode se résout par son TYPE autant
 * que par son nom). Les liaisons le repassent tel quel en {@code Object} — voir
 * {@code AccessorBindings1211} et les règles de l'unité dans
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Il hérite en revanche de {@link net.minecraft.world.WorldView}, où
 * {@code getBiome} est DÉCLARÉE : sans cette relation, un appel typé ici
 * porterait le mauvais propriétaire et serait introuvable après traduction.
 */
public class ClientWorld extends net.minecraft.world.WorldView {

    private ClientWorld() {
    }
}
