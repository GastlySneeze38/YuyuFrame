package net.minecraft.item;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code zx}).
 *
 * <p>Pas de pile « vide » sur cette version : une case vide est {@code null}.
 * {@code count} est un champ PUBLIC (pas de {@code getCount()}).
 */
public final class ItemStack {

    public int count;

    private ItemStack() {
    }

    public Item getItem() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isDamageable() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getDamage() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getMaxDamage() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
