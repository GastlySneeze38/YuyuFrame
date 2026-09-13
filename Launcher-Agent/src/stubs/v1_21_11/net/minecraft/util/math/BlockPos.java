package net.minecraft.util.math;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code is}) — pendant de
 * {@code BlockPos} en 26.1.2 (même nom Yarn, paquet différent).
 *
 * <p>Construit par {@link #ofFloored} et non par un constructeur : cette
 * version n'expose PAS de {@code BlockPos(int, int, int)} dans les mappings
 * (vérifié : seul {@code <init>(Vec3i)} y figure). La fabrique statique fait
 * le plancher elle-même, ce qui est exactement ce que l'appelant veut.
 */
public class BlockPos {

    protected BlockPos() {
    }

    public static BlockPos ofFloored(double x, double y, double z) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
