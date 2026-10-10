package com.yuyuframe.launcheragent.apimixin.v26_1_0.skin;

import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skin d'un compte hors ligne : à la sortie de {@code PlayerInfo.getSkin()},
 * rend celui du launcher pour notre propre joueur quand le serveur ne lui en
 * donne aucun. Tout autre cas reste à vanilla. Voir {@link OwnSkin2610#replace}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.PlayerInfo")
abstract class OwnSkinMixin2610 {

    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$ownSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin own = OwnSkin2610.replace(this, cir.getReturnValue());
        if (own != null) cir.setReturnValue(own);
    }
}
