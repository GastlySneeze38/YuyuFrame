package net.minecraft.entity.player;

import net.minecraft.entity.LivingEntity;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cyv}) — classe DÉCLARANTE du
 * cooldown d'attaque. Voir {@code net.minecraft.entity.Entity} pour le pourquoi
 * de cette hiérarchie.
 *
 * <p>{@code getAttackCooldownProgress(float)} est le pendant Yarn de
 * {@code getAttackStrengthScale(float)} en 26.1.2 : même opération, nom
 * différent — c'est précisément ce qu'absorbe le point d'accès
 * {@code PLAYER_ATTACK_STRENGTH}.
 */
public class PlayerEntity extends LivingEntity {

    protected PlayerEntity() {
    }

    public float getAttackCooldownProgress(float partialTick) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
