package com.mojang.blaze3d.vertex;

/**
 * Stub compile-only (26.1+) — le récepteur de sommets que vanilla passe à
 * {@code GuiElementRenderState.buildVertices}.
 *
 * <p>Ajouté le 2026-08-30 (étape 1c de la refonte du rendu) : c'est en
 * implémentant nous-mêmes {@code GuiElementRenderState} qu'on reprend le
 * contrôle des ATTRIBUTS de sommet, et donc qu'on peut porter les paramètres
 * d'un SDF de coin arrondi — ce que le chemin
 * {@code GuiGraphicsExtractor.fill} ne permettait pas (il passe par
 * {@code ColoredRectangleRenderState}, qui n'écrit que Position + Color).
 *
 * <p>Seules les méthodes qu'on appelle sont déclarées. {@code addVertex(FFF)}
 * plutôt que {@code addVertexWith2DPose} : ça évite un stub JOML pour
 * {@code Matrix3x2fc}, et nos coordonnées sont déjà en espace écran (la pose
 * du HUD est l'identité).
 *
 * <p>Signatures vérifiées sur {@code com/mojang/blaze3d/vertex/VertexConsumer.class}
 * du jar client 26.1.2 — chacune renvoie {@code VertexConsumer} pour
 * l'enchaînement.
 */
public interface VertexConsumer {

    VertexConsumer addVertex(float x, float y, float z);

    /** Couleur ARGB empaquetée. */
    VertexConsumer setColor(int argb);

    /** UV0 — deux flottants. */
    VertexConsumer setUv(float u, float v);

    /** UV1 — deux entiers courts. */
    VertexConsumer setUv1(int u, int v);

    /** UV2 — deux entiers courts (le lightmap en usage vanilla). */
    VertexConsumer setUv2(int u, int v);
}
