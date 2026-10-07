package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Les raccourcis de la barre d'objets posés sur un bouton de souris, dans les
 * inventaires. Vanilla 1.8.9 ne lit ces raccourcis que depuis le clavier
 * ({@code keyPressed} est le seul appelant de {@code handleHotbarKeyPressed},
 * vérifié au javap) : un raccourci « case 5 = clic molette » y est muet. Les
 * versions récentes le font, on fait pareil.
 *
 * <p>Un bouton de souris {@code n} porte le code de touche {@code n - 100},
 * comme partout dans les réglages de touches de cette version. Un clic qui a
 * servi de raccourci n'est pas en plus traité comme un clic.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.HandledScreen")
public abstract class HotbarMouseKeyMixin189 {

    @Inject(method = "mouseClicked(III)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$hotbarMouseKey(int mouseX, int mouseY, int button, CallbackInfo ci) {
        if (((HandledScreenInvoker189) (Object) this).la$handleHotbarKeyPressed(button - 100)) ci.cancel();
    }
}
