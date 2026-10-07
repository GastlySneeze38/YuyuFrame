package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Laisse passer l'étiquette de son propre joueur quand le module « Joueur »
 * la demande ({@code LivingEntityRenderer.hasLabel}, {@code bjl.a(pr)Z}).
 * Tout autre cas reste à vanilla. Voir {@link OwnLabel189}.
 */
@Mixin(targets = "net.minecraft.client.render.entity.LivingEntityRenderer")
public abstract class OwnLabelMixin189 {

    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$ownLabel(@Coerce Object entity, CallbackInfoReturnable<Boolean> cir) {
        if (OwnLabel189.wanted(entity)) cir.setReturnValue(true);
    }
}
