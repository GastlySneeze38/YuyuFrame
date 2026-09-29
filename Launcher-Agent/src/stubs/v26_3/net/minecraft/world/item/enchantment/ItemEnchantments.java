package net.minecraft.world.item.enchantment;

import net.minecraft.core.Holder;

import java.util.Set;

/**
 * Stub compile-only (26.1+) — enchantements portés par un objet, composant
 * {@code DataComponents.ENCHANTMENTS}. Voir {@code SaturationModule}, qui y
 * cherche le niveau de « lunge » pour chiffrer le coût en faim d'un coup de
 * lance.
 *
 * <p>Parcourir {@link #keySet()} et comparer le chemin de registre évite
 * complètement une recherche dans le registre des enchantements (qui
 * demanderait un {@code HolderLookup.Provider}, donc l'accès au monde) : le
 * {@code Holder} porte déjà sa clé, voir {@code Holder.unwrapKey()}.
 */
public abstract class ItemEnchantments {
    public boolean isEmpty() { return true; }
    /** Descripteur réel vérifié : {@code ()Ljava/util/Set;} — le générique est purement compile-time. */
    public Set<Holder<Enchantment>> keySet() { return null; }
    /** Descripteur réel vérifié : {@code (Lnet/minecraft/core/Holder;)I}. */
    public int getLevel(Holder<Enchantment> enchantment) { return 0; }
}
