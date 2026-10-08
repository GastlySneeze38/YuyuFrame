package com.yuyuframe.launcheragent.apimixin.v1_21_11.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * « Nametag » du module Joueur : laisse passer l'étiquette de son propre
 * joueur, que vanilla écarte ({@code LivingEntityRenderer.hasLabel} refuse
 * l'entité depuis laquelle on regarde — relevé dans le bytecode). Tout autre
 * cas reste à vanilla. Voir {@link PlayerBadge1211#ownNametag}.
 */
@Mixin(targets = "net.minecraft.client.render.entity.LivingEntityRenderer")
public abstract class OwnNametagMixin1211 {

    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$ownNametag(@Coerce Object entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
        if (PlayerBadge1211.ownNametag(entity)) cir.setReturnValue(true);
    }
}
