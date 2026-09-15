package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@link HookPoint#KEYBOARD_KEY} sur 1.8.9 — touche envoyée à l'écran ouvert.
 *
 * <p>Pendant du site d'appel de la 1.21.11 ({@code Screen.keyPressed} depuis
 * {@code Keyboard.onKey}) : en 1.8.9 c'est {@code Screen.handleKeyboard()} qui
 * lit l'événement clavier et appelle {@code keyPressed(char, int)} (javap
 * {@code axu.l()V}). Site d'appel et non la méthode appelée : les écrans
 * redéfinissent {@code keyPressed} sans rappeler la version de base, alors que
 * {@code handleKeyboard} est commune. {@code ctx} = le code de touche ({@code Integer}),
 * notifié AVANT que l'écran ne le reçoive.
 *
 * <h2>{@code @ModifyArg} et non {@code @WrapOperation} (2026-09-15)</h2>
 *
 * La première version enveloppait l'appel avec un receveur typé {@code Object},
 * refusé par MixinExtras (il exige le type obfusqué {@code axu}) — tout
 * {@code Screen} serait resté non transformé. Le code de touche est lu au
 * passage et rendu inchangé : même notification, aucun type du jeu.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class KeyboardKeyMixin189 {

    @ModifyArg(method = "handleKeyboard()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;keyPressed(CI)V"),
        index = 1, require = 0)
    private int la$dispatchKeyPress(int keyCode) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBOARD_KEY, keyCode);
        return keyCode;
    }
}
