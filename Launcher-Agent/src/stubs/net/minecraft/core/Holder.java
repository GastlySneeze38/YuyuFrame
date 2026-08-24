package net.minecraft.core;

import net.minecraft.resources.ResourceKey;

import java.util.Optional;

/**
 * Stub compile-only (26.1+) — {@code value()} ajouté (méthode publique,
 * appel direct) pour déballer les Holder&lt;MobEffect&gt; de {@code
 * MobEffects}/{@code MobEffectInstance} (voir {@code NoDarknessModule}/
 * {@code PotionEffectsModule}). Générique — usage brut ({@code Holder} sans
 * paramètre, comme dans {@code ClockTotalTicksMixin261}) reste valide.
 *
 * {@code getKey()} ajouté pour {@code CoordsModule} (biome) — MÊME nom que
 * l'ancien chemin réflexif déjà en place ({@code
 * CoordsModule#registryEntryKeyPath}, jamais revérifié pour 26.1.2
 * spécifiquement mais repris à l'identique par cohérence).
 */
public interface Holder<T> {
    T value();
    Optional<ResourceKey<T>> getKey();
}
