package net.minecraft.item;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dlt}) — pendant de
 * {@code ItemStack} en 26.1.2 (même nom Yarn, mais pas les mêmes membres).
 *
 * <p>Renommages à connaître pour un portage : {@code isDamageable()} s'appelle
 * {@code isDamageableItem()} en 26.1.2 et {@code getDamage()} y est
 * {@code getDamageValue()} ; {@code getMaxDamage()} et {@code getCount()} sont
 * communs.
 */
public class ItemStack implements net.minecraft.component.ComponentsAccess {

    protected ItemStack() {
    }

    @Override
    public Object get(net.minecraft.component.ComponentType type) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getCount() {
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
