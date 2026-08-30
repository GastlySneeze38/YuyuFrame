package net.minecraft.world.item;

/**
 * Stub compile-only (26.1+) — méthodes publiques ajoutées pour {@code
 * ArmorDurabilityModule} (durabilité) — appel direct, aucune donnée
 * exécutée réellement.
 */
public abstract class ItemStack {
    public boolean isEmpty() { return true; }
    public Item getItem() { return null; }
    public boolean isDamageableItem() { return false; }
    public int getDamageValue() { return 0; }
    public int getMaxDamage() { return 0; }
    /** Taille de la pile — voir {@code ArmorDurabilityModule}, qui l'affiche pour un objet empilé. Signature vérifiée sur le jar 26.1.2 ({@code getCount()I}). */
    public int getCount() { return 0; }
}
