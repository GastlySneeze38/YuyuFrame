package net.minecraft.entity;

import net.minecraft.item.ItemStack;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code pr}).
 *
 * <p>Les effets se désignent par leur id NUMÉRIQUE ({@code hasStatusEffect(int)},
 * {@code removeEffect(int)}) — pas de registre. {@code getArmorSlot(i)} :
 * 0 = bottes … 3 = casque (lu dans le bytecode : {@code inventory.armor[i]}).
 */
public class LivingEntity extends Entity {

    /** Animation de swing en cours ({@code pr.ar}, public). */
    public boolean handSwinging;

    /** Ticks écoulés dans le swing, {@code -1} = relancé ({@code pr.as}, public). */
    public int handSwingTicks;

    protected LivingEntity() {
    }

    /** Avancement du swing, 0 à 1 ({@code pr.l(F)F}). */
    public float getHandSwingProgress(float tickDelta) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public java.util.Collection getStatusEffectInstances() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean hasStatusEffect(int id) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void removeEffect(int id) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getHealth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getMaxHealth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ItemStack getArmorSlot(int slot) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
