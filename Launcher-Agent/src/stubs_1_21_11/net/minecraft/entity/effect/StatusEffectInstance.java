package net.minecraft.entity.effect;

import net.minecraft.registry.entry.RegistryEntry;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cfm}) — pendant de
 * {@code MobEffectInstance} en 26.1.2.
 *
 * <p>Renommage à connaître pour un portage : la durée illimitée se lit
 * {@code isInfinite()} ici et {@code isInfiniteDuration()} en 26.1.2 — c'est
 * précisément le genre d'écart que {@code PlayerEffect} absorbe une fois pour
 * toutes côté liaisons.
 */
public class StatusEffectInstance {

    protected StatusEffectInstance() {
    }

    /** Poignée vers l'effet — {@code RegistryEntry<StatusEffect>} en jeu. */
    public RegistryEntry getEffectType() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Niveau interne, 0 = niveau I. */
    public int getAmplifier() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Durée restante en ticks ; sans objet si {@link #isInfinite()}. */
    public int getDuration() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isInfinite() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
