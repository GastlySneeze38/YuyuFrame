package com.yuyuframe.launcheragent.apimixin.v1_21_11.player;

import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le logo dans la liste Tab ({@code PlayerListHud}, {@code gkg}). Pendant de
 * {@code TabListLogoMixin261}, sous les noms Yarn.
 *
 * <ul>
 *   <li>{@code getPlayerName} rend le pseudo précédé de la place du logo :
 *       vanilla mesure alors la colonne avec cette place. Seulement pendant le
 *       dessin de la liste — la méthode est publique et sert ailleurs.</li>
 *   <li>Le seul {@code drawTextWithShadow(TextRenderer, Text, …)} de
 *       {@code render} est celui qui dessine un pseudo (vérifié javap sur le
 *       jar 1.21.11) ; il est redirigé pour poser le logo juste après.</li>
 * </ul>
 * Aucun type du jeu ici : tout est repris par {@link PlayerBadge1211}.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.PlayerListHud")
public abstract class TabListLogoMixin1211 {

    private static final String RENDER =
        "render(Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V";

    @Inject(method = RENDER, at = @At("HEAD"), require = 0)
    private void la$tabBegins(@Coerce Object context, int scaledWindowWidth, @Coerce Object scoreboard,
                              @Coerce Object objective, CallbackInfo ci) {
        PlayerBadge.tabBegins();
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0)
    private void la$tabEnds(@Coerce Object context, int scaledWindowWidth, @Coerce Object scoreboard,
                            @Coerce Object objective, CallbackInfo ci) {
        PlayerBadge.tabEnds();
    }

    @Inject(method = "getPlayerName(Lnet/minecraft/client/network/PlayerListEntry;)Lnet/minecraft/text/Text;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void la$reserveLogo(@Coerce Object entry, CallbackInfoReturnable<Object> cir) {
        Object padded = PlayerBadge1211.tabName(entry, cir.getReturnValue());
        if (padded != null) cir.setReturnValue(padded);
    }

    @Redirect(method = RENDER,
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"),
              require = 0)
    private void la$drawNameAndLogo(@Coerce Object context, @Coerce Object textRenderer, @Coerce Object name,
                                    int x, int y, int color) {
        PlayerBadge1211.drawTabName(context, textRenderer, name, x, y, color);
    }
}
