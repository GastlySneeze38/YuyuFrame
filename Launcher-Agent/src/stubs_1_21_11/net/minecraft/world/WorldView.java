package net.minecraft.world;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dwr}) — classe DÉCLARANTE de
 * {@code getBiome}.
 *
 * <p>C'est ICI qu'elle est déclarée, pas sur {@code ClientWorld} : javac écrit
 * le type STATIQUE du receveur comme propriétaire de l'appel, et Yarn range
 * chaque méthode sous sa déclarante. Typer le receveur en {@code ClientWorld}
 * produirait {@code ClientWorld.getBiome}, introuvable après traduction —
 * exactement le piège payé en v1076 sur {@code getX}/{@code getHealth}.
 *
 * <p>Interface en jeu ; déclarée en classe ici, ce qui suffit pour porter le
 * bon propriétaire d'appel.
 */
public class WorldView {

    protected WorldView() {
    }

    /** Biome à cette position — {@code RegistryEntry<Biome>} en jeu. */
    public RegistryEntry getBiome(BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
