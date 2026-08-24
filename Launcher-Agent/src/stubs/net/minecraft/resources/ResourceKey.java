package net.minecraft.resources;

/**
 * Stub compile-only (26.1+) — {@code getValue()} publique, MÊME nom que
 * l'ancien chemin réflexif déjà en place pour {@code RegistryKey} (voir
 * {@code CoordsModule#registryEntryKeyPath}, jamais revérifié pour 26.1.2
 * spécifiquement mais repris à l'identique par cohérence plutôt que de
 * deviner un autre nom).
 */
public interface ResourceKey<T> {
    net.minecraft.resources.Identifier getValue();
}
