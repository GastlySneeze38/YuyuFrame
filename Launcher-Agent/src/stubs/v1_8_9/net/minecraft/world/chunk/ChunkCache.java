package net.minecraft.world.chunk;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code adv}) — {@code WorldRegion} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class ChunkCache implements net.minecraft.world.BlockView {

    protected ChunkCache() {
    }

    /** {@code adv.<init>} — vue figée du monde entre deux coins, élargie de {@code margin} blocs. */
    public ChunkCache(net.minecraft.world.World world, net.minecraft.util.math.BlockPos min, net.minecraft.util.math.BlockPos max, int margin) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public net.minecraft.block.BlockState getBlockState(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public net.minecraft.world.biome.Biome getBiome(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
