package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.entity.Entity;

/**
 * Rappelle le dessin d'origine d'une particule, que
 * {@link ParticleCullMixin189} a détourné. Code typé, traduit au chargement :
 * le mixin, lui, ne nomme aucun type du jeu.
 *
 * <p>À part de {@code RendererHooks189} : cette classe ne touche ni JOML ni
 * le moteur, elle doit se charger même quand le renderer est indisponible.
 */
@YarnNamed
public final class ParticleDraw189 {

    private ParticleDraw189() {
    }

    public static void draw(Object particle, Object buffer, Object camera, float tickDelta,
                            float rotationX, float rotationXZ, float rotationZ, float rotationYZ, float rotationXY) {
        ((Particle) particle).draw((BufferBuilder) buffer, (Entity) camera, tickDelta,
            rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
    }
}
