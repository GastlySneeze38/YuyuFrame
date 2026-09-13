package net.minecraft.entity.player;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dhe}).
 *
 * <p>{@code getFoodLevel()} et {@code getSaturationLevel()} sont publics ; il
 * n'existe PAS de getter pour l'épuisement ({@code exhaustion} est un champ
 * privé, vérifié dans Yarn) — {@code FOOD_EXHAUSTION} passe donc par un
 * accessor Mixin à part, voir {@code HungerManagerAccessor1211}.
 */
public class HungerManager {

    private HungerManager() {
    }

    public int getFoodLevel() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getSaturationLevel() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
