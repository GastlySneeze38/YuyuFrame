package net.minecraft.resource;

import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code bbc}) — classe DÉCLARANTE de
 * {@code getResource}.
 *
 * <p>INTERFACE dans le jeu — voir
 * {@link net.minecraft.registry.entry.RegistryEntry} pour ce que coûte
 * l'erreur inverse.
 *
 * <p>{@code ResourceManager} en hérite, mais c'est ICI que Yarn range la
 * méthode, et c'est donc ce nom que doit porter l'appel.
 */
public interface ResourceFactory {

    /** {@code Optional<Resource>} en jeu — brut ici, l'effacement suffit. */
    java.util.Optional getResource(Identifier id);
}
