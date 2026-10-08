package com.yuyuframe.launcheragent.apimixin.v26_3.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second temps du logo sur l'étiquette : en tête de la soumission du pseudo
 * ({@code EntityRenderer.submitNameDisplay}, la surcharge finale à cinq
 * paramètres par laquelle passent toutes les entités). Seuls les états
 * marqués au premier temps reçoivent le logo. Voir
 * {@link PlayerBadge263#beforeNameDisplay}.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.EntityRenderer")
abstract class NameTagLogoMixin263 {

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At("HEAD"), require = 0)
    private void la$submitLogo(@Coerce Object state, @Coerce Object poseStack, @Coerce Object collector,
                               @Coerce Object camera, int offset, CallbackInfo ci) {
        PlayerBadge263.beforeNameDisplay(state, poseStack, collector, camera, offset);
    }
}
