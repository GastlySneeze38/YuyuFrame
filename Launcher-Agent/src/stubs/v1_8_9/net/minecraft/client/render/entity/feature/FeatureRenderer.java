package net.minecraft.client.render.entity.feature;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code blb}) — une couche dessinée
 * par-dessus le modèle d'une entité vivante (armure, objet tenu, cape…).
 */
public interface FeatureRenderer {

    /** {@code blb.a(pr, FFFFFFF)} */
    void render(net.minecraft.entity.LivingEntity entity, float limbAngle, float limbDistance, float tickDelta,
                float age, float headYaw, float headPitch, float scale);
}
