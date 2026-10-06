package net.minecraft.client.render;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bib}) — {@code Frustum} chez Feather aussi.
 *
 * <p>Calcule les plans de coupe depuis les matrices OpenGL courantes ; c'est
 * l'instance que le {@code CullingCameraView} de vanilla consulte.
 */
public class Frustum extends BaseFrustum {

    protected Frustum() {
    }

    /** {@code bib.a} — recalcule les plans et rend l'instance unique. */
    public static BaseFrustum getInstance() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
