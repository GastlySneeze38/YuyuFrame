package net.minecraft.client.render;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bid}) — {@code FrustumData} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class BaseFrustum {

    /** {@code bid.a} — {@code frustum} chez Feather. */
    public float[][] homogeneousCoordinates;

    /** {@code bid.c} — {@code modelMatrix} chez Feather. */
    public float[] modelMatrix;

    protected BaseFrustum() {
    }

    /** {@code bid.b} — {@code contains} chez Feather. */
    public boolean isInFrustum(double p0, double p1, double p2, double p3, double p4, double p5) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
