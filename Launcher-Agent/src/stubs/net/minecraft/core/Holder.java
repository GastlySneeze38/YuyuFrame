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
 * <p>BUG TROUVÉ (2026-08-27) : ce stub déclarait {@code getKey()}, repris
 * « par cohérence » de l'ancien chemin réflexif et — sa propre javadoc
 * l'admettait — jamais revérifié pour 26.1.2. Cette méthode <b>n'existe
 * pas</b> : le vrai nom est {@code unwrapKey()} (vérifié en lisant
 * {@code net/minecraft/core/Holder.class} dans le jar client réel). Le biome
 * de {@code CoordsModule} partait donc en {@code NoSuchMethodError} avalé par
 * son {@code catch (Throwable)}, et ne fonctionnait que via le repli
 * réflexif ; sa suppression a rendu la panne visible.
 */
public interface Holder<T> {
    T value();

    /** Clé de registre, vide si le Holder n'est pas enregistré. Nom réel 26.1.2 — voir la javadoc de classe. */
    Optional<ResourceKey<T>> unwrapKey();
}
