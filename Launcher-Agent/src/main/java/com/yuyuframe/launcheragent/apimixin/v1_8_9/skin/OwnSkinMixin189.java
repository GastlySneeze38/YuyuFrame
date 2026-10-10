package com.yuyuframe.launcheragent.apimixin.v1_8_9.skin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skin d'un compte hors ligne ({@code PlayerListEntry}, {@code bdc}) : à la
 * sortie de {@code getSkinTexture} et de {@code getModel}, rend ceux du
 * launcher pour notre propre joueur quand le serveur ne lui donne aucun skin.
 * Tout autre cas reste à vanilla. Voir {@link OwnSkin189}.
 *
 * <p>{@code require = 0} partout : si l'un des deux ne s'applique pas, le
 * joueur garde l'apparence de vanilla.
 */
@Mixin(targets = "net.minecraft.client.network.PlayerListEntry")
public abstract class OwnSkinMixin189 {

    @Inject(method = "getSkinTexture()Lnet/minecraft/util/Identifier;", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$ownSkinTexture(CallbackInfoReturnable<Object> cir) {
        Object own = OwnSkin189.skinTexture(this);
        if (own != null) cir.setReturnValue(own);
    }

    @Inject(method = "getModel()Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 0)
    private void la$ownSkinModel(CallbackInfoReturnable<String> cir) {
        String own = OwnSkin189.model(this);
        if (own != null) cir.setReturnValue(own);
    }
}
