package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.optimodule.ParticleRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cible : net.minecraft.client.particle.Particle (official beb, qui hérite
 * de net.minecraft.entity.Entity — official pk), méthode draw(BufferBuilder,
 * Entity,F,F,F,F,F,F)V (officiel "a", method_1283) — vérifiée par
 * désassemblage bytecode réel de ParticleManager.renderParticles : AUCUN
 * culling par distance là-dedans, chaque particule de chaque bucket (jusqu'à
 * 4000 par bucket, plafond vanilla déjà existant) est dessinée peu importe
 * sa distance à la caméra. Voir ParticleRenderDistanceModule pour le détail
 * du problème et de l'impact.
 *
 * IMPORTANT : ~20 sous-classes de Particle REDÉFINISSENT draw() avec leur
 * propre rendu (vérifié en désassemblant TOUTES les classes du package
 * particle) — dont FireSmokeParticle (fumée feu/lave), LavaEmberParticle
 * (bulles de lave), LargeExplosionParticle (explosion), ExplosionEmitterParticle
 * — précisément les particules concernées par "lag en regardant de la
 * lave"/"lag sur explosion". Un Mixin visant SEULEMENT la classe de base
 * beb ne les intercepterait PAS (Mixin ne s'applique qu'à la classe exacte
 * ciblée, pas aux sous-classes qui redéfinissent la méthode). D'où la liste
 * de {@code targets} ci-dessous : la classe de base (couvre toutes les
 * sous-classes qui N'ont PAS redéfini draw(), ex: ExplosionSmokeParticle,
 * WaterBubbleParticle...) + les ~20 classes qui redéfinissent, chacune
 * recevant indépendamment le même @Inject (Mixin applique les injecteurs
 * d'une classe à CHAQUE cible listée dans {@code targets}).
 *
 * Position de la particule : héritée d'Entity, champs publics x/y/z
 * (officiel s/t/u, vérifiés via javap — publics, donc @Shadow direct malgré
 * l'héritage, aucun souci d'accès).
 *
 * Position de la caméra (interpolée) : champs STATIQUES sur Particle lui-même,
 * aw/ax/ay (officiel, non nommés côté Yarn) — remplis une fois par frame en
 * tête de ParticleManager.renderParticles juste avant la boucle de rendu,
 * donc toujours à jour au moment où draw() est appelé, et hérités tels
 * quels par toutes les sous-classes.
 *
 * Mixin directement DANS la méthode cible (pas un call-site redirigé) — même
 * technique que MixinCachedStringWidth189 : aucun paramètre de la cible
 * capturé (juste CallbackInfo), donc aucun risque du bug de capture Object
 * découvert plus tôt sur ce bootstrap (voir MixinLabelRenderDistance189).
 */
@Mixin(targets = {
    "net.minecraft.client.particle.Particle",
    "net.minecraft.client.particle.BarrierParticle",
    "net.minecraft.client.particle.SnowballParticle",
    "net.minecraft.client.particle.DamageParticle",
    "net.minecraft.client.particle.FlameParticle",
    "net.minecraft.client.particle.FootstepParticle",
    "net.minecraft.client.particle.EmotionParticle",
    "net.minecraft.client.particle.LargeExplosionParticle",
    "net.minecraft.client.particle.ExplosionEmitterParticle",
    "net.minecraft.client.particle.ItemPickupParticle",
    "net.minecraft.client.particle.LavaEmberParticle",
    "net.minecraft.client.particle.ElderGuardianAppearanceParticle",
    "net.minecraft.client.particle.NoteParticle",
    "net.minecraft.client.particle.CloudParticle",
    "net.minecraft.client.particle.PortalParticle",
    "net.minecraft.client.particle.RedstoneParticle",
    "net.minecraft.client.particle.FireSmokeParticle",
    "net.minecraft.client.particle.SnowShovelParticle",
    "net.minecraft.client.particle.SpellParticle",
    "net.minecraft.client.particle.BlockDustParticle",
    "net.minecraft.client.particle.EmitterParticle"
})
public abstract class MixinParticleRenderDistance189 {

    @Shadow public double s;
    @Shadow public double t;
    @Shadow public double u;

    @Shadow private static double aw;
    @Shadow private static double ax;
    @Shadow private static double ay;

    @Inject(method = "a(Lbfd;Lpk;FFFFFF)V", at = @At("HEAD"), cancellable = true)
    private void la$cullDistantParticles(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("particle-render-distance");
            if (module == null || !module.isEnabled() || !(module instanceof ParticleRenderDistanceModule)) return;
            float maxDist = ((ParticleRenderDistanceModule) module).maxDistance;
            double dx = s - aw;
            double dy = t - ax;
            double dz = u - ay;
            if (dx * dx + dy * dy + dz * dz > (double) (maxDist * maxDist)) {
                ci.cancel();
            }
        } catch (Throwable ex) {
            LauncherLog.err("[LauncherAgent] MixinParticleRenderDistance189: " + ex);
        }
    }
}
