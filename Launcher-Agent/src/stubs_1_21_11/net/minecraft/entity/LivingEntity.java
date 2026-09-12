package net.minecraft.entity;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code chl}) — classe DÉCLARANTE de la
 * santé. Voir {@link Entity} pour le pourquoi de cette hiérarchie.
 */
public class LivingEntity extends Entity {

    protected LivingEntity() {
    }

    public float getHealth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getMaxHealth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Pendant de {@code hasEffect(Holder)} en 26.1.2. */
    public boolean hasStatusEffect(net.minecraft.registry.entry.RegistryEntry effect) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Pendant de {@code removeEffect(Holder)} en 26.1.2. */
    public boolean removeStatusEffect(net.minecraft.registry.entry.RegistryEntry effect) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Effets actifs — {@code Collection<StatusEffectInstance>} en jeu, déclarée
     * BRUTE ici pour coller au descripteur réel
     * ({@code ()Ljava/util/Collection;}), qui est tout ce que voit la JVM.
     *
     * <p>Troisième nom de cette méthode au fil des versions
     * ({@code getStatusEffectInstances} → {@code getStatusEffects} →
     * {@code getActiveEffects} en 26.1.2) : voir {@code AccessPoint#PLAYER_ACTIVE_EFFECTS}.
     */
    public java.util.Collection getStatusEffects() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
