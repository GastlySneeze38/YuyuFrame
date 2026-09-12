package net.minecraft.registry.entry;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code jd}) — poignée OPAQUE : une
 * entrée de registre (pendant de {@code Holder} en 26.1.2).
 *
 * <p>Générique en jeu ({@code RegistryEntry<T>}) ; déclaré BRUT ici, ce qui
 * suffit : après effacement, seuls comptent les descripteurs. {@link #value()}
 * rend donc {@link Object} — exactement ce que porte le descripteur réel
 * ({@code ()Ljava/lang/Object;}), l'appelant faisant le transtypage qui va
 * bien.
 */
public class RegistryEntry {

    protected RegistryEntry() {
    }

    /** La valeur pointée — {@code Holder.value()} en 26.1.2. */
    public Object value() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Identifiant de registre sous forme de chaîne, ex. {@code "minecraft:speed"}.
     *
     * <p>Raccourci de CETTE version : la 26.1.2 n'a pas d'équivalent direct et
     * doit passer par {@code unwrapKey()} puis {@code identifier()}. Sur une
     * entrée DIRECTE (sans clé de registre) le jeu rend une forme entre
     * crochets plutôt qu'un identifiant — d'où le {@code registryId} déclaré
     * « éventuellement illisible » dans {@code PlayerEffect}.
     */
    public String getIdAsString() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
