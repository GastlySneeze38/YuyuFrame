package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SONDE TEMPORAIRE (2026-10-06) — chronomètre les étapes du dessin d'une
 * entité vivante ({@code LivingEntityRenderer}, {@code bjl}) : modèle,
 * étiquette de nom, et chaque couche. Voir {@link EntityCostProbe189}. Ne
 * change rien à l'image. À retirer une fois la mesure faite.
 */
@Mixin(targets = "net.minecraft.client.render.entity.LivingEntityRenderer")
public abstract class EntityCostProbeMixin189 {

    @Inject(method = "renderModel(Lnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), require = 0)
    private void la$beginModel(@Coerce Object entity, float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        EntityCostProbe189.beginModel();
    }

    @Inject(method = "renderModel(Lnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At("RETURN"), require = 0)
    private void la$endModel(@Coerce Object entity, float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        EntityCostProbe189.endModel(entity);
    }

    @Inject(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V", at = @At("HEAD"), require = 0)
    private void la$beginLabel(@Coerce Object entity, double x, double y, double z, CallbackInfo ci) {
        EntityCostProbe189.beginLabel();
    }

    @Inject(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V", at = @At("RETURN"), require = 0)
    private void la$endLabel(@Coerce Object entity, double x, double y, double z, CallbackInfo ci) {
        EntityCostProbe189.endLabel(entity);
    }

    @Redirect(method = "renderFeatures(Lnet/minecraft/entity/LivingEntity;FFFFFFF)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/entity/feature/FeatureRenderer;render("
                           + "Lnet/minecraft/entity/LivingEntity;FFFFFFF)V"),
              require = 0)
    private void la$timeFeature(@Coerce Object feature, @Coerce Object entity, float limbAngle, float limbDistance,
                                float tickDelta, float age, float headYaw, float headPitch, float scale) {
        EntityCostProbe189.renderFeature(feature, entity, limbAngle, limbDistance, tickDelta, age, headYaw, headPitch, scale);
    }
}
