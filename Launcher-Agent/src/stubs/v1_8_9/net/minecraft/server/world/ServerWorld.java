package net.minecraft.server.world;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code le}) — {@code ServerWorld} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class ServerWorld extends net.minecraft.world.World {

    protected ServerWorld() {
    }

    @Override
    public net.minecraft.world.biome.Biome getBiome(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
