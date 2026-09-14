package net.minecraft.item;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code zs}).
 *
 * <p>Pas de composant FOOD sur cette version : l'aliment EST la classe de
 * l'item. {@code getSaturation} rend le MODIFICATEUR (0,6 pour un steak), pas
 * les points gagnés — vanilla calcule {@code faim × modificateur × 2} dans
 * {@code HungerManager.add(IF)} (vérifié bytecode). {@code alwaysEdible} est
 * privé : voir {@code FoodItemAccessor189}.
 */
public class FoodItem extends Item {

    private FoodItem() {
    }

    public int getHungerPoints(ItemStack stack) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getSaturation(ItemStack stack) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
