package net.minecraft.entity.effect;

import net.minecraft.registry.entry.RegistryEntry;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cfo}) — pendant de
 * {@code MobEffects} en 26.1.2.
 *
 * <p>Seul {@code DARKNESS} est déclaré : on ne stube que ce que les liaisons
 * servent réellement (voir {@code AccessorBindings1211.effectById}). Constante
 * de type OBJET, donc aucun risque d'inlining par javac.
 *
 * <p>À noter pour un futur portage : {@code DARKNESS} n'existe pas avant la 1.19.
 */
public final class StatusEffects {

    public static final RegistryEntry DARKNESS;

    static {
        DARKNESS = stub();
    }

    private StatusEffects() {
    }

    private static RegistryEntry stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
