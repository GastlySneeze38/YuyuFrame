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

    /**
     * Pièce portée à cet emplacement — {@code getItemBySlot(EquipmentSlot)} en
     * 26.1.2. Déclarée ICI et non sur {@code PlayerEntity} : c'est la classe
     * déclarante réelle (mappings), et javac écrit le type STATIQUE du receveur
     * comme propriétaire de l'appel.
     */
    public net.minecraft.item.ItemStack getEquippedStack(net.minecraft.entity.EquipmentSlot slot) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Pile tenue dans cette main — {@code getItemInHand(InteractionHand)} en 26.1.2. */
    public net.minecraft.item.ItemStack getStackInHand(net.minecraft.util.Hand hand) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Main principale (droitier/gaucher) — même nom qu'en 26.1.2, type {@code Arm} au lieu de {@code HumanoidArm}. */
    public net.minecraft.util.Arm getMainArm() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Vol à l'élytre — {@code isFallFlying()} en 26.1.2. Renommée en
     * {@code isGliding} sur cette ligne de versions.
     */
    public boolean isGliding() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
