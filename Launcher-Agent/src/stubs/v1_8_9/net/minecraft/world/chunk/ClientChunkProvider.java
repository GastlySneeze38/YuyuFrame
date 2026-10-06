package net.minecraft.world.chunk;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bcz}) — {@code ClientChunkCache} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class ClientChunkProvider {

    protected ClientChunkProvider() {
    }

    /** {@code bcz.b} — {@code unloadChunk} chez Feather. */
    public void unloadChunk(int p0, int p1) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bcz.c} — {@code generateChunk} chez Feather. */
    public net.minecraft.world.chunk.Chunk getOrGenerateChunk(int p0, int p1) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
