package net.minecraft.component;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code kd}) — classe DÉCLARANTE de
 * {@code get(ComponentType)}.
 *
 * <p>Déclarée ICI et pas sur {@code ItemStack} : javac écrit le type STATIQUE
 * du receveur comme propriétaire de l'appel, et Yarn range chaque méthode sous
 * sa déclarante (leçon v1076). Un appel typé {@code ItemStack.get} serait
 * introuvable après traduction.
 */
public class ComponentsAccess {

    protected ComponentsAccess() {
    }

    /**
     * Composant de ce type, ou {@code null}. Générique en jeu
     * ({@code <T> T get(ComponentType<? extends T>)}) ; rendu {@link Object}
     * ici, ce qui correspond au descripteur réel après effacement.
     */
    public Object get(ComponentType type) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
