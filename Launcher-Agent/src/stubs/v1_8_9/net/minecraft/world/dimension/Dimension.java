package net.minecraft.world.dimension;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code anm}).
 *
 * <p>Membres : la hauteur des nuages, lue par leur rendu
 * ({@code celeritas/v1_8_9/sky/CloudRenderer189}).
 */
public abstract class Dimension {

    protected Dimension() {
    }

    /** {@code anm.o()} — Nether, End : pas de lumière du ciel. */
    public boolean hasNoSkylight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code anm.f()} */
    public float getCloudHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
