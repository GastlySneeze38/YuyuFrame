package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La liste Tab aux couleurs du module « Joueur » ({@code PlayerListHud},
 * {@code awh}) : le nom d'un joueur YuyuFrame y prend le même fond, le même
 * logo et les mêmes couleurs que son étiquette au-dessus de la tête. Tout le
 * travail est dans {@link PlayerListStyle189}, dont la javadoc explique
 * pourquoi ces deux points d'entrée suffisent.
 *
 * <p>{@code require = 0} partout : si l'un des deux ne s'applique pas, la
 * liste reste celle de vanilla.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.PlayerListHud")
public abstract class PlayerListStyleMixin189 {

    @Inject(method = "getPlayerName(Lnet/minecraft/client/network/PlayerListEntry;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void la$reserveLogoRoom(@Coerce Object entry, CallbackInfoReturnable<String> cir) {
        String styled = PlayerListStyle189.reserve(entry, cir.getReturnValue());
        if (styled != null) {
            cir.setReturnValue(styled);
        }
    }

    @Redirect(method = "render(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"),
            require = 0)
    private int la$drawStyledName(@Coerce Object textRenderer, String text, float x, float y, int color) {
        return PlayerListStyle189.draw(textRenderer, text, x, y, color);
    }
}
