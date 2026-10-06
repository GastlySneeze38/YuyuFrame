package net.minecraft.util.math;

/** Stub compile-only 1.8.9, nom Yarn legacy ({@code cj}) — pas de {@code ofFloored} : constructeur entier. */
public class BlockPos extends Vec3i {

    public BlockPos(int x, int y, int z) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    // Les cinq portent le nom obfusqué {@code cj.a} ; seule la signature les distingue.

    public BlockPos add(double x, double y, double z) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public BlockPos add(int x, int y, int z) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public BlockPos add(Vec3i pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public BlockPos offset(Direction direction) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public BlockPos offset(Direction direction, int distance) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code cj$a} — position réutilisable, pour ne pas allouer dans une boucle. */
    public static final class Mutable extends BlockPos {

        public Mutable() {
            super(0, 0, 0);
        }

        public Mutable(int x, int y, int z) {
            super(x, y, z);
        }

        /** {@code cj$a.c(III)} — {@code set} chez Feather. */
        public Mutable setPosition(int x, int y, int z) {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
