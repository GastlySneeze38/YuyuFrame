package com.yuyuframe.launcheragent.apimixin.v26_1_2.player;

import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le logo dans la liste Tab ({@code PlayerTabOverlay}).
 *
 * <ul>
 *   <li>{@code getNameForDisplay} rend le pseudo précédé de la place du logo :
 *       vanilla mesure alors la colonne avec cette place. Seulement pendant le
 *       dessin de la liste — la méthode est publique et sert ailleurs.</li>
 *   <li>Le seul {@code text(Font, Component, …)} d'{@code extractRenderState}
 *       est celui qui dessine un pseudo (vérifié javap : en-tête et pied de
 *       page passent par la surcharge {@code FormattedCharSequence}) ; il est
 *       redirigé pour poser le logo juste après. Le pseudo y est le même objet
 *       que celui rendu plus haut, on le reconnaît par identité.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.gui.components.PlayerTabOverlay")
abstract class TabListLogoMixin261 {

    private static final String EXTRACT =
        "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V";

    @Inject(method = EXTRACT, at = @At("HEAD"), require = 0)
    private void la$tabBegins(@Coerce Object graphics, int screenWidth, @Coerce Object scoreboard, @Coerce Object objective,
                              CallbackInfo ci) {
        PlayerBadge.tabBegins();
    }

    @Inject(method = EXTRACT, at = @At("RETURN"), require = 0)
    private void la$tabEnds(@Coerce Object graphics, int screenWidth, @Coerce Object scoreboard, @Coerce Object objective,
                            CallbackInfo ci) {
        PlayerBadge.tabEnds();
    }

    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void la$reserveLogo(@Coerce Object info, CallbackInfoReturnable<Component> cir) {
        Component padded = PlayerBadge261.tabName(info, cir.getReturnValue());
        if (padded != null) cir.setReturnValue(padded);
    }

    @Redirect(method = EXTRACT,
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
              require = 0)
    private void la$drawNameAndLogo(GuiGraphicsExtractor graphics, Font font, Component name, int x, int y, int color) {
        graphics.text(font, name, x, y, color);
        PlayerBadge261.tabLogo(graphics, name, x, y);
    }
}
