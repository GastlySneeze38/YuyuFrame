package net.minecraft.component;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code ki}) — registre des types de
 * composants d'item.
 *
 * <p>Constantes de type OBJET : aucun risque d'inlining par javac (la règle de
 * l'unité ne vise que les primitifs).
 */
public final class DataComponentTypes {

    public static final ComponentType FOOD;

    public static final ComponentType ENCHANTMENTS;

    static {
        FOOD = stub();
        ENCHANTMENTS = stub();
    }

    private DataComponentTypes() {
    }

    private static ComponentType stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
