package net.minecraft.component;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code kd}) — classe DÉCLARANTE de
 * {@code get(ComponentType)}.
 *
 * <p>INTERFACE dans le jeu : la déclarer en classe produirait un
 * {@code invokevirtual} au lieu d'un {@code invokeinterface}, donc un
 * {@code IncompatibleClassChangeError} au premier appel (voir
 * {@link net.minecraft.registry.entry.RegistryEntry} pour le détail du piège).
 *
 * <p>Déclarée ici et pas sur {@code ItemStack} : javac écrit le type STATIQUE
 * du receveur comme propriétaire de l'appel, et Yarn range chaque méthode sous
 * sa déclarante (leçon v1076).
 */
public interface ComponentsAccess {

    /**
     * Composant de ce type, ou {@code null}. Générique en jeu
     * ({@code <T> T get(ComponentType<? extends T>)}) ; rendu {@link Object}
     * ici, ce qui correspond au descripteur réel après effacement.
     */
    Object get(ComponentType type);
}
