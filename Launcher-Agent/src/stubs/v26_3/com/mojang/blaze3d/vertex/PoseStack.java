package com.mojang.blaze3d.vertex;

/**
 * Stub compile-only (26.1+) — la pile de transformations du rendu du monde.
 * Signatures vérifiées par javap sur le jar client 26.1.2 ; seules celles
 * qu'on appelle sont déclarées (logo du module « Joueur »).
 */
public class PoseStack {

    public void pushPose() {}

    public void popPose() {}

    public void translate(double x, double y, double z) {}

    public void translate(float x, float y, float z) {}

    public void scale(float x, float y, float z) {}

    /** 26.3 : a remplacé {@code mulPose(Quaternionfc)}, qui n'existe plus (vérifié javap sur le jar 26.3). */
    public void rotate(org.joml.Quaternionfc rotation) {}

    public Pose last() { return null; }

    /** {@code PoseStack$Pose} — une transformation figée, passée telle quelle à {@code VertexConsumer.addVertex}. */
    public static final class Pose {
        private Pose() {}
    }
}
