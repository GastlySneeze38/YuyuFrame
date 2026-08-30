package net.minecraft.resources;

/**
 * Stub compile-only (26.1+).
 *
 * <p>Deux bugs successifs le 2026-08-27, tous deux invisibles à la
 * compilation et fatals au runtime :
 * <ol>
 *   <li>la méthode déclarée s'appelait {@code getValue()}, reprise du nom
 *       Yarn de l'ancien {@code RegistryKey} et — la javadoc d'alors
 *       l'admettait — jamais revérifiée. Elle n'existe pas : c'est
 *       {@code identifier()} ({@code registry()} en étant le pendant pour le
 *       registre) ;</li>
 *   <li>corrigé cela, le biome échouait TOUJOURS, avec cette fois
 *       {@code IncompatibleClassChangeError: Found class
 *       net.minecraft.resources.ResourceKey, but interface was expected} —
 *       ce stub était déclaré {@code interface} alors que la vraie
 *       {@code ResourceKey} est une <b>classe</b>. javac émettait donc un
 *       {@code invokeinterface} là où la JVM attend un {@code invokevirtual}.</li>
 * </ol>
 * Le genre (classe/interface/enum) d'un stub fait partie de son contrat, au
 * même titre que les noms et les types de retour — {@code tools/audit_stubs.py}
 * vérifie les trois.
 *
 * <p>Vérifié en lisant {@code net/minecraft/resources/ResourceKey.class} du
 * jar client 26.1.2 : classe, sans superclasse ni interface.
 */
public class ResourceKey<T> {
    private ResourceKey() {}

    /** Identifiant de l'entrée (ex. {@code minecraft:plains}). Nom réel 26.1.2. */
    public Identifier identifier() { return null; }
}
