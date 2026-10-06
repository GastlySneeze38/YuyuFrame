package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.RendererHooks189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ne calcule pas les particules hors du champ de la caméra.
 *
 * <p>Le {@code ParticleManager} de la 1.8.9 ({@code bec}) regroupe déjà ses
 * particules en un envoi par couche. Ce qui coûte, c'est le calcul de chacune
 * — sa lumière (une lecture du monde) et ses quatre sommets — fait même pour
 * celles qui sont derrière le joueur.
 *
 * <p>{@code @Redirect} sur le site d'appel et non {@code @Inject} en tête de
 * {@code Particle.draw} : la plupart des particules redéfinissent cette
 * méthode, une injection sur la classe de base n'en verrait qu'une partie.
 * {@link Coerce} sur le receveur et les arguments : MixinExtras refuse un type
 * {@code Object} pour un type obfusqué.
 *
 * <p>Actif seulement avec le renderer de terrain : le test réutilise ses
 * matrices de l'image, sans rien relire dans OpenGL.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleManager")
public abstract class ParticleCullMixin189 {

    @Redirect(method = "renderParticles(Lnet/minecraft/entity/Entity;F)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/particle/Particle;draw("
                           + "Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"),
              require = 0)
    private void la$drawIfVisible(@Coerce Object particle, @Coerce Object buffer, @Coerce Object camera,
                                  float tickDelta, float rotationX, float rotationXZ, float rotationZ,
                                  float rotationYZ, float rotationXY) {
        if (RendererSwitch.active() && RendererHooks189.isParticleCulled(particle)) {
            return;
        }
        ParticleDraw189.draw(particle, buffer, camera, tickDelta, rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
    }
}
