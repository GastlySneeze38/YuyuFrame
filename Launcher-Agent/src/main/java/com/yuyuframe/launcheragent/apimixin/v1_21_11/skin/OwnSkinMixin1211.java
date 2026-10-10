package com.yuyuframe.launcheragent.apimixin.v1_21_11.skin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skin d'un compte hors ligne ({@code PlayerListEntry}, {@code hiq}) : à la
 * sortie de {@code getSkinTextures}, rend celui du launcher pour notre propre
 * joueur quand le serveur ne lui en donne aucun. Pendant de
 * {@code OwnSkinMixin261}, sous les noms Yarn.
 *
 * <p>Aucun type du jeu ici : tout est repris par {@link OwnSkin1211}.
 */
@Mixin(targets = "net.minecraft.client.network.PlayerListEntry")
public abstract class OwnSkinMixin1211 {

    @Inject(method = "getSkinTextures()Lnet/minecraft/entity/player/SkinTextures;", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$ownSkin(CallbackInfoReturnable<Object> cir) {
        Object own = OwnSkin1211.replace(this, cir.getReturnValue());
        if (own != null) cir.setReturnValue(own);
    }
}
