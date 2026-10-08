package com.yuyuframe.launcheragent.apimixin.v26_1_2.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * « Nametag » du module Joueur : laisse passer l'étiquette de son propre
 * joueur, que vanilla écarte ({@code LivingEntityRenderer.shouldShowName}
 * refuse l'entité depuis laquelle on regarde — relevé dans le bytecode).
 * Tout autre cas reste à vanilla. Voir {@link PlayerBadge261#ownNametag}.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.LivingEntityRenderer")
abstract class OwnNametagMixin261 {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$ownNametag(@Coerce Object entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (PlayerBadge261.ownNametag(entity)) cir.setReturnValue(true);
    }
}
