package net.minecraft.client.render;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code fzp}).
 *
 * <p>NOMS DIFFÉRENTS DE LA 26.1.2, à ne pas confondre en portant du code :
 * {@code vertex} (et non {@code addVertex}), {@code color} ({@code setColor}),
 * {@code texture} ({@code setUv}), {@code overlay} ({@code setUv1}),
 * {@code light} ({@code setUv2}). Vérifiés dans {@code yarn-1.21.11-mergedv2.jar}
 * et par {@code javap} sur le jar réel.
 */
public interface VertexConsumer {

    VertexConsumer vertex(float x, float y, float z);

    /** Couleur empaquetée ARGB. */
    VertexConsumer color(int argb);

    VertexConsumer texture(float u, float v);

    /** Créneau {@code UV1} du format de sommet. */
    VertexConsumer overlay(int u, int v);

    /** Créneau {@code UV2} du format de sommet. */
    VertexConsumer light(int u, int v);
}
