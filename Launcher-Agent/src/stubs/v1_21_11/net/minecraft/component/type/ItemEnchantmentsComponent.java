package net.minecraft.component.type;

import net.minecraft.registry.entry.RegistryEntry;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dsu}) — pendant d'
 * {@code ItemEnchantments} en 26.1.2.
 *
 * <p>Renommage à connaître : l'ensemble des enchantements se lit
 * {@code getEnchantments()} ici et {@code keySet()} en 26.1.2.
 */
public class ItemEnchantmentsComponent {

    protected ItemEnchantmentsComponent() {
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code Set<RegistryEntry<Enchantment>>} en jeu — brut ici, l'effacement suffit. */
    public java.util.Set getEnchantments() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getLevel(RegistryEntry enchantment) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
