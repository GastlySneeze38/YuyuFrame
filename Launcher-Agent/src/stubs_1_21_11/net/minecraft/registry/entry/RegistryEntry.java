package net.minecraft.registry.entry;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code jd}) — poignée d'une entrée de
 * registre (pendant de {@code Holder} en 26.1.2).
 *
 * <h2>INTERFACE, et ce n'est pas un détail</h2>
 *
 * Le type réel est une interface. Déclarer le stub en CLASSE faisait émettre à
 * javac un {@code invokevirtual} là où la JVM attend un
 * {@code invokeinterface} : la compilation passe, le banc passe (le membre
 * existe bien), et le jeu lève un {@code IncompatibleClassChangeError} au
 * premier appel. C'est ce qui a fait échouer la lecture du biome et des effets
 * de potion (v1095, corrigé v1096).
 *
 * <p>Générique en jeu ({@code RegistryEntry<T>}) ; déclaré BRUT ici, ce qui
 * suffit : après effacement, seuls comptent les descripteurs — d'où
 * {@link #value()} qui rend {@link Object}, exactement ce que porte le
 * descripteur réel.
 */
public interface RegistryEntry {

    /** La valeur pointée — {@code Holder.value()} en 26.1.2. */
    Object value();

    /**
     * Identifiant de registre sous forme de chaîne, ex. {@code "minecraft:speed"}.
     *
     * <p>Raccourci de CETTE version : la 26.1.2 n'a pas d'équivalent direct et
     * doit passer par {@code unwrapKey()} puis {@code identifier()}. Sur une
     * entrée DIRECTE (sans clé de registre) le jeu rend une forme entre
     * crochets plutôt qu'un identifiant — d'où le {@code registryId} déclaré
     * « éventuellement illisible » dans {@code PlayerEffect}.
     */
    String getIdAsString();
}
