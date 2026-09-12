package net.minecraft.resource;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code baz}) — le gestionnaire de
 * ressources du jeu.
 *
 * <p>INTERFACE dans le jeu, comme {@link ResourceFactory} dont elle hérite —
 * déclarer l'un ou l'autre en classe produit un {@code invokevirtual} fautif
 * (voir {@link net.minecraft.registry.entry.RegistryEntry}).
 *
 * <p>Aucun membre propre : tout ce qu'on lui demande ({@code getResource}) est
 * déclaré par {@code ResourceFactory}, et c'est ce nom-là que doit porter
 * l'appel.
 */
public interface ResourceManager extends ResourceFactory {
}
