package net.minecraft.world.chunk;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code amy}) — {@code WorldChunk} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class Chunk {

    /** {@code amy.a} — {@code chunkX} chez Feather. */
    public int chunkX;

    /** {@code amy.b} — {@code chunkZ} chez Feather. */
    public int chunkZ;

    protected Chunk() {
    }

    /** {@code amy.a} — {@code getBlock} chez Feather. */
    public net.minecraft.block.Block method_9131(int p0, int p1, int p2) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.a} — {@code getBlock} chez Feather. */
    public net.minecraft.block.Block getBlockAtPos(net.minecraft.util.math.BlockPos p0) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.a} — {@code getBlockEntity} chez Feather. */
    public net.minecraft.block.entity.BlockEntity getBlockEntity(net.minecraft.util.math.BlockPos p0, net.minecraft.world.chunk.Chunk.Status p1) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.b} — {@code getHeight} chez Feather. */
    public int getHighestBlockY(int p0, int p1) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.b} — {@code tick} chez Feather. */
    public void populateBlockEntities(boolean p0) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.c} — {@code isEmpty} chez Feather. */
    public boolean areSectionsEmptyBetween(int p0, int p1) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.d} — {@code unload} chez Feather. */
    public void unloadFromWorld() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.f} — {@code isEmpty} chez Feather. */
    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.f} — {@code getHeight} chez Feather. */
    public int getHighestBlockY(net.minecraft.util.math.BlockPos p0) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.g} — {@code getBlockState} chez Feather. */
    public net.minecraft.block.BlockState method_9154(net.minecraft.util.math.BlockPos p0) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.h} — {@code getSections} chez Feather. */
    public net.minecraft.world.chunk.ChunkSection[] getBlockStorage() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy.s} — {@code getEntities} chez Feather. */
    public net.minecraft.util.TypeFilterableList[] getEntities() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amy$a} — façon de créer le block entity manquant, demandée par {@link #getBlockEntity}. */
    public enum Status {
        IMMEDIATE, QUEUED, CHECK;
    }
}
