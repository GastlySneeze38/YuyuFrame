package net.minecraft.world.chunk;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code amz}) — une tranche de
 * chunk de 16 blocs de haut.
 */
public class ChunkSection {

    protected ChunkSection() {
    }

    /** {@code amz.a} — aucun bloc dans cette tranche de 16 de haut. */
    public boolean isEmpty() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amz.b(III)} — coordonnées locales, 0 à 15. */
    public net.minecraft.block.Block getBlock(int x, int y, int z) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amz.g()} — 4 096 identifiants d'états de bloc, à l'indice {@code y << 8 | z << 4 | x}. Le tableau lui-même. */
    public char[] getBlockStates() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amz.h()} */
    public ChunkNibbleArray getBlockLight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code amz.i()} — {@code null} dans une dimension sans ciel. */
    public ChunkNibbleArray getSkyLight() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
