package net.minecraft.entity.player;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code wn}).
 *
 * <p>Ni recharge d'attaque, ni main secondaire, ni élytres, ni nage sur cette
 * version : les points d'accès correspondants servent une constante.
 */
public class PlayerEntity extends LivingEntity {

    public PlayerAbilities abilities;

    protected PlayerEntity() {
    }

    public HungerManager getHungerManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean canConsume(boolean ignoreHunger) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isSpectator() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ItemStack getMainHandStack() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
