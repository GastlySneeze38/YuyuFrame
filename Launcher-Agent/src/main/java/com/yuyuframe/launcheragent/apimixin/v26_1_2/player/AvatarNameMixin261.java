package com.yuyuframe.launcheragent.apimixin.v26_1_2.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Premier temps du logo sur l'étiquette : à la sortie de
 * {@code AvatarRenderer.extractRenderState}, là où l'on tient encore le
 * joueur (donc son profil) en même temps que l'état qui sera dessiné.
 * Voir {@link PlayerBadge261#afterExtract}.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.player.AvatarRenderer")
abstract class AvatarNameMixin261 {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void la$reserveLogo(@Coerce Object entity, @Coerce Object state, float partialTick, CallbackInfo ci) {
        PlayerBadge261.afterExtract(entity, state);
    }
}
