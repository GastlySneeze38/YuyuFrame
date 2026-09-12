package net.minecraft.world;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dwr}) — classe DÉCLARANTE de
 * {@code getBiome}.
 *
 * <p>INTERFACE dans le jeu. La déclarer en classe faisait émettre un
 * {@code invokevirtual} : le biome ne se lisait jamais en 1.21.11, avec un
 * {@code IncompatibleClassChangeError} à chaque frame — voir
 * {@link net.minecraft.registry.entry.RegistryEntry}.
 *
 * <p>Déclarée ici et pas sur {@code ClientWorld} : javac écrit le type STATIQUE
 * du receveur comme propriétaire de l'appel, et Yarn range chaque méthode sous
 * sa déclarante (leçon v1076).
 */
public interface WorldView {

    /** Biome à cette position — {@code RegistryEntry<Biome>} en jeu. */
    RegistryEntry getBiome(BlockPos pos);
}
