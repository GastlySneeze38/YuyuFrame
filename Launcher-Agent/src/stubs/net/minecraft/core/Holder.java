package net.minecraft.core;

/**
 * Stub compile-only (26.1+) — {@code value()} ajouté (méthode publique,
 * appel direct) pour déballer les Holder&lt;MobEffect&gt; de {@code
 * MobEffects}/{@code MobEffectInstance} (voir {@code NoDarknessModule}/
 * {@code PotionEffectsModule}). Générique — usage brut ({@code Holder} sans
 * paramètre, comme dans {@code ClockTotalTicksMixin261}) reste valide.
 */
public interface Holder<T> {
    T value();
}
