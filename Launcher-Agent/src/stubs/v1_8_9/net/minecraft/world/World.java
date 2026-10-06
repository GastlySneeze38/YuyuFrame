package net.minecraft.world;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code adm}).
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas
 * ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public abstract class World implements BlockView {

    protected World() {
    }

    /** {@code adm.t} */
    public net.minecraft.world.dimension.Dimension dimension;

    /** {@code adm.e(F)} */
    public net.minecraft.util.math.Vec3d getCloudColor(float tickDelta) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.f} — {@code entities} chez Feather : toutes les entités chargées. */
    public java.util.List loadedEntities;

    /** {@code adm.N} — {@code getChunkSource} chez Feather. */
    public net.minecraft.world.chunk.ChunkProvider getChunkProvider() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Exigée par {@link BlockView} ; appeler sur un receveur typé {@code BlockView}. */
    @Override
    public net.minecraft.block.BlockState getBlockState(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.U} — {@code getHeight} chez Feather : hauteur maximale de construction. */
    public int getMaxBuildHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.a(II)} — {@code getChunkAt} chez Feather, coordonnées de chunk. */
    public net.minecraft.world.chunk.Chunk getChunk(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.a(BlockPos,Z)} — {@code isChunkLoaded} chez Feather. */
    public boolean isLoaded(net.minecraft.util.math.BlockPos pos, boolean allowEmpty) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.e(BlockPos)} — {@code isChunkLoaded} chez Feather. */
    public boolean blockExists(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adm.m(BlockPos)} — {@code getHeight} chez Feather : le bloc le plus haut de la colonne. */
    public net.minecraft.util.math.BlockPos getHighestBlock(net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
